#!/usr/bin/env python3
"""Mock de DESENVOLVIMENTO do dict-gateway e do core-ledger (sistemas externos, fora deste repositório).

Implementa só o contrato que o ms-pix-manager chama (CoreLedgerHttpAdapter / DictGatewayHttpAdapter), em memória:

  DICT    POST /dict/v1/entries            registra a chave        -> {cid, registeredAt}
          POST /dict/v1/entries/deletion   remove a chave          -> {}
          POST /dict/v1/entries/lookup     consulta a chave        -> {owner, account, ownershipDate} | 404
  LEDGER  POST /ledger/v1/reservations                             -> {ledgerRef} | 422 {code: INSUFFICIENT_FUNDS}
          POST /ledger/v1/reservations/{key}/confirmation          -> {ledgerRef}
          POST /ledger/v1/reservations/{key}/cancellation          -> {ledgerRef}
  OTP     POST /otp/v1/messages            "envia" o código (guarda em memória) -> 202
          GET  /otp/v1/last?destination=   último código enviado a esse e-mail/telefone (SÓ dev/teste: permite completar a prova de posse)
  WEBHOOK RECEPTOR (cliente fictício) em HTTPS :8443, certificado de DEV autoassinado (dev-cert.pem, SAN mocks/localhost/127.0.0.1):
          POST /webhook-receiver/ok/pix      200  (valida a assinatura x-pix-signature e guarda o que chegou)
          POST /webhook-receiver/fail/pix    503  (para ver retry e DEAD)
          GET  /webhook-receiver/last        último webhook recebido (inclui signatureValid)
  GET /health · GET /__state (estado em memória, para depuração)

Regras para simular cenários (valores fixos, só dev):
  • lookup de chave que contém "naoexiste"  -> 404 (chave inexistente no DICT)
  • lookup de chave registrada por este mock -> devolve o titular/conta registrados
  • lookup de qualquer outra chave           -> titular fictício (Maria Beneficiária, CPF 11144477735, ISPB 60746948)
  • reserva com amount == 99.99 -> 422 INSUFFICIENT_FUNDS (saldo insuficiente)
  • reserva com amount == 66.66 -> 422 ACCOUNT_BLOCKED (conta bloqueada)
  • reserva com amount == 50.50 -> 503 (falha técnica do core, para ver retry/circuit breaker)
  (valores baixos de propósito: o limite p/ NOVO favorecido é 100.00 e o noturno 1000.00, aplicados ANTES do ledger)
"""
import base64, hashlib, hmac, json, os, ssl, threading, time, uuid, datetime
from decimal import Decimal
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

STATE = {"dict": {}, "reservations": {}, "otp": [], "webhooks": []}
WEBHOOK_KEY = base64.b64decode(os.environ.get("WEBHOOK_SIGNING_KEY_B64", "ZGV2LXdlYmhvb2stc2lnbmluZy1rZXktMzItYnl0ZXMhIQ=="))   # = PIX_WEBHOOK_SIGNING_KEY de dev
now = lambda: datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z")


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print("%s %s -> %s" % (self.command, self.path, args[1] if len(args) > 1 else ""), flush=True)

    def reply(self, status, body=None):
        raw = json.dumps(body if body is not None else {}).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    raw_body = b""

    def body(self):
        # o cliente JDK do app envia corpo "chunked" (sem Content-Length)
        if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
            raw = b""
            while True:
                size = int(self.rfile.readline().split(b";")[0].strip() or b"0", 16)
                if size == 0:
                    self.rfile.readline()
                    break
                raw += self.rfile.read(size)
                self.rfile.readline()
            self.raw_body = raw
            return json.loads(raw or b"{}")
        n = int(self.headers.get("Content-Length") or 0)
        self.raw_body = self.rfile.read(n) if n else b""
        return json.loads(self.raw_body or b"{}")

    def do_GET(self):
        if self.path == "/health":
            return self.reply(200, {"status": "UP"})
        if self.path == "/webhook-receiver/last":
            return self.reply(200, STATE["webhooks"][-1]) if STATE["webhooks"] else self.reply(404, {"message": "nenhum webhook recebido"})
        if self.path.startswith("/otp/v1/last"):
            dest = self.path.split("destination=", 1)[1] if "destination=" in self.path else ""
            from urllib.parse import unquote
            dest = unquote(dest)
            found = [m for m in STATE["otp"] if m["destination"] == dest]
            return self.reply(200, found[-1]) if found else self.reply(404, {"message": "nenhum código enviado a esse destino"})
        if self.path == "/__state":
            return self.reply(200, STATE)
        self.reply(404, {"message": "rota desconhecida"})

    def do_POST(self):
        p, b = self.path.split("?")[0], self.body()
        if p == "/dict/v1/entries":
            cid = "cid-" + uuid.uuid4().hex[:16]
            STATE["dict"][b["key"]] = {"cid": cid, "keyType": b["keyType"], "account": b["account"], "holder": b["holder"], "at": now()}
            return self.reply(200, {"cid": cid, "registeredAt": now()})
        if p == "/dict/v1/entries/deletion":
            STATE["dict"].pop(b.get("key"), None)
            return self.reply(200, {})
        if p == "/dict/v1/entries/lookup":
            key = b.get("key", "")
            if "naoexiste" in key:
                return self.reply(404, {"message": "Chave não encontrada no DICT"})
            e = STATE["dict"].get(key)
            if e:
                return self.reply(200, {"owner": {"document": e["holder"]["document"], "name": e["holder"]["name"]},
                                        "account": {"ispb": e["account"]["ispb"], "branch": e["account"]["branch"],
                                                    "number": e["account"]["number"], "type": e["account"]["type"]},
                                        "ownershipDate": e["at"]})
            return self.reply(200, {"owner": {"document": "11144477735", "name": "Maria Beneficiária"},
                                    "account": {"ispb": "60746948", "branch": "0001", "number": "987654", "type": "CACC"},
                                    "ownershipDate": "2020-01-01T00:00:00Z"})
        if p.startswith("/webhook-receiver/") and p.endswith("/pix"):
            mode = p.split("/")[2]
            raw = self.raw_body
            ts = self.headers.get("x-pix-timestamp", "")
            sig = self.headers.get("x-pix-signature", "")
            expected = "v1=" + hmac.new(WEBHOOK_KEY, (ts + "." + raw.decode()).encode(), hashlib.sha256).hexdigest()
            fresh = ts.isdigit() and abs(time.time() - int(ts)) <= 300
            STATE["webhooks"].append({"mode": mode, "at": now(), "signatureValid": hmac.compare_digest(sig, expected), "timestampFresh": fresh,
                                      "body": json.loads(raw or b"{}")})
            del STATE["webhooks"][:-50]
            return self.reply(503 if mode == "fail" else 200, {})
        if p == "/otp/v1/messages":
            STATE["otp"].append({"channel": b.get("channel"), "destination": b.get("destination"), "code": b.get("code"), "validForSeconds": b.get("validForSeconds"), "at": now()})
            del STATE["otp"][:-50]                                   # guarda só os 50 últimos
            return self.reply(202, {"status": "ACCEPTED"})
        if p == "/ledger/v1/reservations":
            amount = Decimal(b.get("amount", "0"))
            if amount == Decimal("50.50"):
                return self.reply(503, {"message": "core indisponível (simulado)"})
            if amount == Decimal("66.66"):
                return self.reply(422, {"code": "ACCOUNT_BLOCKED", "message": "Conta bloqueada (simulado)"})
            if amount == Decimal("99.99"):
                return self.reply(422, {"code": "INSUFFICIENT_FUNDS", "message": "Saldo insuficiente (simulado)"})
            ref = "led-" + uuid.uuid4().hex[:12]
            STATE["reservations"][b["idempotencyKey"]] = {"ledgerRef": ref, "amount": b["amount"], "status": "RESERVED", "purpose": b.get("purpose")}
            return self.reply(200, {"ledgerRef": ref})
        if p.startswith("/ledger/v1/reservations/") and p.endswith(("/confirmation", "/cancellation")):
            key = p.split("/")[4]
            r = STATE["reservations"].setdefault(key, {"ledgerRef": "led-" + uuid.uuid4().hex[:12]})
            r["status"] = "CONFIRMED" if p.endswith("confirmation") else "CANCELLED"
            return self.reply(200, {"ledgerRef": r["ledgerRef"]})
        self.reply(404, {"message": "rota desconhecida"})


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "8080"))
    tls_port = int(os.environ.get("TLS_PORT", "8443"))
    here = os.path.dirname(os.path.abspath(__file__))
    secure = ThreadingHTTPServer(("0.0.0.0", tls_port), Handler)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(os.path.join(here, "dev-cert.pem"), os.path.join(here, "dev-key.pem"))
    secure.socket = context.wrap_socket(secure.socket, server_side=True)
    threading.Thread(target=secure.serve_forever, daemon=True).start()
    print("mock dict-gateway + core-ledger em :%d (HTTP) e receptor de webhook em :%d (HTTPS)" % (port, tls_port), flush=True)
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
