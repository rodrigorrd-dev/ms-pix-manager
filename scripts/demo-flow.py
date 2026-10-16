#!/usr/bin/env python3
"""Roteiro ponta a ponta do Pix no ambiente local (docker compose): chaves, BR Code/QR, pagamento, recebimento e devolução.

  python3 scripts/demo-flow.py            # roda tudo, imprime cada passo, exit 1 se algum passo divergir do esperado

Usa o mock do dict-gateway/core-ledger (serviço `mocks`) e scripts/simulate-spi.py (faz o papel do spi-gateway via Kafka).
Só stdlib. Chaves criadas são excluídas ao final (limite de 5 chaves vivas por conta PF).
"""
import json, os, subprocess, sys, time, urllib.parse, urllib.request, uuid

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
B = os.environ.get("PIX_BASE_URL", "http://localhost:8088")
KC = os.environ.get("KEYCLOAK_URL", "http://localhost:18180") + "/realms/pix/protocol/openid-connect/token"
MOCKS = os.environ.get("MOCKS_URL", "http://localhost:18090")
ACC = "00000000-0000-0000-0000-000000000001"; SEED_KEY = "123e4567-e89b-42d3-a456-426655440000"
ok_all = []


def token(client):
    d = urllib.parse.urlencode({"grant_type": "client_credentials", "client_id": client, "client_secret": "dev-secret-local-only"}).encode()
    return json.load(urllib.request.urlopen(urllib.request.Request(KC, d)))["access_token"]


O, P = token("pix-backoffice-dev"), token("pix-partner-dev")


def call(method, path, tk, body=None, headers=None):
    h = {"Authorization": "Bearer " + tk, "Content-Type": "application/json", **(headers or {})}
    r = urllib.request.Request(B + path, data=json.dumps(body).encode() if body is not None else None, method=method, headers=h)
    try:
        x = urllib.request.urlopen(r); return x.status, x.headers, json.loads(x.read() or b"null")
    except urllib.error.HTTPError as e:
        raw = e.read(); return e.code, e.headers, (json.loads(raw) if raw else None)


def script(name, *args):
    return subprocess.run([sys.executable, os.path.join(ROOT, "scripts", name), *args], capture_output=True, text=True, check=True).stdout.strip()


def step(title, cond, info=""):
    ok_all.append(bool(cond)); print(("  OK   " if cond else "  FALHA ") + title + (" — " + str(info) if info else ""))


def wait(fn, want, timeout=40):
    end = time.time() + timeout
    while time.time() < end:
        v = fn()
        if v == want:
            return v
        time.sleep(2)
    return fn()


def free_keys():
    _, _, l = call("GET", "/v1/pix-keys?accountId=%s&size=100" % ACC, O)
    for k in l["content"]:
        if k["status"] not in ("DELETED", "REJECTED") and k["id"] != "22222222-2222-4222-8222-222222222222":
            _, h, _ = call("GET", "/v1/pix-keys/" + k["id"], O)
            call("POST", "/v1/pix-keys/%s/deletion" % k["id"], O, {"reason": "USER_REQUESTED"}, {"If-Match": h["ETag"]})


def pay(body, wanted):
    s, _, j = call("POST", "/v1/payment-orders", O, body, {"Idempotency-Key": str(uuid.uuid4())})
    return j


free_keys()
print("1) CHAVES — registrar e ativar no DICT (mock)")
s, _, evp = call("POST", "/v1/pix-keys", O, {"accountId": ACC, "pixKeyType": "EVP"}, {"Idempotency-Key": str(uuid.uuid4())})
step("EVP (chave aleatória) registrada", s == 201 and evp["status"] == "PENDING_DICT", evp["status"])
st = wait(lambda: call("GET", "/v1/pix-keys/" + evp["id"], O)[2]["status"], "ACTIVE")
step("o sweeper registrou no DICT e a chave virou ACTIVE", st == "ACTIVE", st)
email = "demo%s@example.com" % int(time.time())
s, _, em = call("POST", "/v1/pix-keys", O, {"accountId": ACC, "pixKeyType": "EMAIL", "pixKey": email}, {"Idempotency-Key": str(uuid.uuid4())})
step("chave de e-mail nasce PENDING_OWNERSHIP_PROOF (exige prova de posse)", s == 201 and em["status"] == "PENDING_OWNERSHIP_PROOF", em["status"])
s, _, ch = call("POST", "/v1/pix-keys/%s/ownership-proof/challenges" % em["id"], O)
step("código OTP enviado (202); a resposta traz canal e destino mascarado, NUNCA o código", s == 202 and ch["channel"] == "EMAIL" and "code" not in ch, ch.get("destination"))
code = json.load(urllib.request.urlopen(MOCKS + "/otp/v1/last?destination=" + urllib.parse.quote(email)))["code"]   # o 'mock' do serviço de notificação guarda o código
wrong = "000000" if code != "000000" else "000001"
s, _, bad = call("POST", "/v1/pix-keys/%s/ownership-proof/verifications" % em["id"], O, {"code": wrong})
step("código errado ⇒ 422 PIX-422-014 com as tentativas restantes", s == 422 and bad["code"] == "PIX-422-014", bad.get("detail"))
s, _, ok = call("POST", "/v1/pix-keys/%s/ownership-proof/verifications" % em["id"], O, {"code": code})
step("código certo ⇒ chave vai a PENDING_DICT", s == 200 and ok["status"] == "PENDING_DICT", ok.get("status"))
st = wait(lambda: call("GET", "/v1/pix-keys/" + em["id"], O)[2]["status"], "ACTIVE")
step("o job registra no DICT (mock) e a chave de e-mail fica ACTIVE", st == "ACTIVE", st)

print("2) QR CODE / BR CODE — gerar e decodificar")
static = script("brcode.py", "static", "--key", "outra@example.com", "--name", "Maria Beneficiaria", "--amount", "12.34")
s, _, dec = call("POST", "/v1/payment-orders/decodings", O, {"payload": static})
step("BR Code estático gerado e decodificado pela API", s == 200 and dec["amount"] == "12.34" and not dec["dynamic"], {k: dec[k] for k in ("pixKeyMasked", "amount", "merchantName")})
tx = "demo" + uuid.uuid4().hex[:28]
s, _, cob = call("PUT", "/api/pix/v2/cob/" + tx, P, {"calendario": {"expiracao": 3600}, "devedor": {"cpf": "52998224725", "nome": "Fulano"},
                                                      "valor": {"original": "50.00"}, "chave": SEED_KEY})
step("cobrança imediata criada (ATIVA, com location)", s == 201 and cob["status"] == "ATIVA", cob.get("location"))
dyn = script("brcode.py", "dynamic", "--url", cob["location"], "--amount", "50.00")
s, _, d2 = call("POST", "/v1/payment-orders/decodings", O, {"payload": dyn})
step("BR Code dinâmico (copia e cola da cobrança) decodificado", s == 200 and d2["dynamic"], dyn[:60] + "...")

print("2b) WEBHOOK — o recebedor é avisado de cada Pix recebido")
hook = "https://mocks:8443/webhook-receiver/ok"            # receptor fictício (HTTPS, certificado de dev) dentro da rede do compose
s, _, wh = call("PUT", "/api/pix/v2/webhook/" + SEED_KEY, P, {"webhookUrl": hook})
step("webhook cadastrado na chave (201 novo / 200 atualizado)", s in (200, 201) and wh["webhookUrl"] == hook, s)
s, _, bad = call("PUT", "/api/pix/v2/webhook/" + SEED_KEY, P, {"webhookUrl": "http://mocks:8080/x"})
step("URL http:// é SEMPRE recusada (422 PIX-422-017); em produção também são recusados hosts internos (anti-SSRF)", s == 422 and bad["code"] == "PIX-422-017", s)

print("3) FAZER UM PIX — por chave, depois por QR estático")
p = pay({"payerAccountId": ACC, "method": "KEY", "amount": "25.90", "destination": {"pixKeyType": "EMAIL", "pixKey": "outra@example.com"}}, 201)
step("pagamento por chave criado; DICT (mock) resolveu o favorecido", p["destinationName"] == "Maria Beneficiária", p["status"])
st = wait(lambda: call("GET", "/v1/payment-orders/" + p["id"], O)[2]["status"], "RESERVED")
step("saldo reservado no core-ledger (mock) → RESERVED", st == "RESERVED", st)
script("simulate-spi.py", "payment-status", p["endToEndId"], "SENT")
st = wait(lambda: call("GET", "/v1/payment-orders/" + p["id"], O)[2]["status"], "SENT_TO_SPI")
step("SPI confirmou o envio → SENT_TO_SPI", st == "SENT_TO_SPI", st)
script("simulate-spi.py", "payment-status", p["endToEndId"], "SETTLED")
st = wait(lambda: call("GET", "/v1/payment-orders/" + p["id"], O)[2]["status"], "SETTLED")
step("SPI liquidou → SETTLED", st == "SETTLED", st)
q = pay({"payerAccountId": ACC, "method": "QR_STATIC", "qrPayload": static}, 201)
step("pagamento por QR estático criado com o valor do código", q.get("amount") == "12.34", q.get("status"))
call("POST", "/v1/payment-orders/%s/cancellation" % q["id"], O, None, {"If-Match": call("GET", "/v1/payment-orders/" + q["id"], O)[1]["ETag"]})
def qr_dyn(name):
    return script("brcode.py", "dynamic", "--url", "mocks:8443/qr/v2/" + name)      # PSP do recebedor fictício (payload JWS assinado)


dq = pay({"payerAccountId": ACC, "method": "QR_DYNAMIC", "qrPayload": qr_dyn("v-12.34")}, 201)
step("QR dinâmico: o app buscou o payload JWS, validou a assinatura e pagou o valor da cobrança (12.34), sem o cliente informar valor",
     dq.get("amount") == "12.34" and dq.get("method") == "QR_DYNAMIC", dq.get("amount"))
call("POST", "/v1/payment-orders/%s/cancellation" % dq["id"], O, None, {"If-Match": call("GET", "/v1/payment-orders/" + dq["id"], O)[1]["ETag"]})
for name, why in (("adulterado", "assinatura inválida (payload alterado)"), ("paga", "cobrança já CONCLUIDA"), ("expirada", "cobrança vencida")):
    bad = pay({"payerAccountId": ACC, "method": "QR_DYNAMIC", "qrPayload": qr_dyn(name)}, 422)
    step("QR dinâmico recusado: " + why + " (PIX-422-018)", bad.get("code") == "PIX-422-018", bad.get("code"))
bad = pay({"payerAccountId": ACC, "method": "QR_DYNAMIC", "qrPayload": dyn}, 422)
step("QR dinâmico cuja URL não resolve é recusado sem erro 500", bad.get("code") == "PIX-422-018", bad.get("code"))

print("3b) PIX AGENDADO — criar, listar ocorrências e cancelar")
import datetime as _dt
first = (_dt.date.today() + _dt.timedelta(days=3)).isoformat()
s, _, sch = call("POST", "/v1/scheduled-payments", O, {"payerAccountId": ACC, "recurrence": "MONTHLY", "method": "KEY", "amount": "10.00", "firstDate": first,
                                                       "occurrencesTotal": 3, "destination": {"pixKeyType": "EMAIL", "pixKey": "outra@example.com"}},
                 {"Idempotency-Key": str(uuid.uuid4())})
step("agendamento mensal (3x) criado", s == 201 and sch["status"] == "ACTIVE", s)
s, _, occ = call("GET", "/v1/scheduled-payments/%s/occurrences" % sch["id"], O)
step("3 ocorrências SCHEDULED, a 1ª na data pedida", s == 200 and len(occ) == 3 and occ[0]["dueDate"] == first and all(o["status"] == "SCHEDULED" for o in occ), s)
s, _, bad = call("POST", "/v1/scheduled-payments", O, {"payerAccountId": ACC, "recurrence": "ONCE", "method": "KEY", "amount": "10.00",
                                                       "firstDate": _dt.date.today().isoformat(), "destination": {"pixKeyType": "EMAIL", "pixKey": "outra@example.com"}},
                 {"Idempotency-Key": str(uuid.uuid4())})
step("data de hoje é recusada (PIX-422-061)", s == 422 and bad["code"] == "PIX-422-061", bad.get("code"))
s, h, _ = call("GET", "/v1/scheduled-payments/" + sch["id"], O)
s, _, c = call("POST", "/v1/scheduled-payments/%s/cancellation" % sch["id"], O, None, {"If-Match": h["ETag"]})
step("cancelado: agendamento e ocorrências", s == 200 and c["status"] == "CANCELLED", s)

print("3c) MED — o cliente RECEBEU um Pix suspeito: abrir, aceitar (bloqueio no core), devolver com natureza MED")
out = script("simulate-spi.py", "pix-received", "--amount", "20.00")
med_e2e = [l.split()[1] for l in out.splitlines() if l.startswith("endToEndId:")][0]
wait(lambda: call("GET", "/api/pix/v2/pix/" + med_e2e, P)[0], 200)
s, _, mc = call("POST", "/v1/med-cases", O, {"role": "REPORTED", "endToEndId": med_e2e, "caseType": "FRAUD", "description": "golpe do falso suporte (demo)", "layer": 1},
                {"Idempotency-Key": str(uuid.uuid4())})
step("caso MED aberto (REPORTED, OPEN)", s == 201 and mc["status"] == "OPEN" and mc["trackedAmount"] == "20.00", s)
s, _, dup = call("POST", "/v1/med-cases", O, {"role": "REPORTED", "endToEndId": med_e2e, "caseType": "FRAUD", "description": "de novo"}, {"Idempotency-Key": str(uuid.uuid4())})
step("segundo caso para o mesmo Pix/camada é recusado (409)", s == 409, s)
s, _, mc = call("POST", "/v1/med-cases/%s/acceptance" % mc["id"], O)
step("aceito → ACCEPTED", s == 200 and mc["status"] == "ACCEPTED", s)
bl = wait(lambda: call("GET", "/v1/med-cases/" + mc["id"], O)[2]["blockedAmount"], "20.00")
step("o core bloqueou o valor (bloqueio cautelar 20.00)", bl == "20.00", bl)
s, _, mc = call("POST", "/v1/med-cases/%s/refund" % mc["id"], O)
step("devolução MED solicitada → REFUNDING", s == 200 and mc["status"] == "REFUNDING", s)
rtr = wait(lambda: (call("GET", "/api/pix/v2/pix/" + med_e2e, P)[2].get("devolucoes") or [{}])[0].get("rtrId"), None) or call("GET", "/api/pix/v2/pix/" + med_e2e, P)[2]["devolucoes"][0]["rtrId"]
script("simulate-spi.py", "refund-status", rtr, "SETTLED", "--e2e", med_e2e)
st = wait(lambda: call("GET", "/v1/med-cases/" + mc["id"], O)[2]["status"], "CLOSED")
step("SPI liquidou a devolução → caso CLOSED (acompanhamento automático)", st == "CLOSED", st)
s, _, hist = call("GET", "/v1/med-cases/%s/history" % mc["id"], O)
step("histórico auditável: OPEN → ACCEPTED → (bloqueio) → REFUNDING → CLOSED", s == 200 and [h["to"] for h in hist][0] == "OPEN" and hist[-1]["to"] == "CLOSED", len(hist))

print("3d) PIX AUTOMÁTICO (pagador) — solicitação, aceite, cobrança e cancelamento")
rec = "RR" + uuid.uuid4().hex[:27]
s, _, au = call("POST", "/v1/automatic-authorizations", O, {"idRec": rec, "payerAccountId": ACC, "payerDocument": "52998224725", "receiverDocument": "11222333000181",
                                                              "receiverName": "Academia Forma", "contract": "CONTRATO-77", "objectDescription": "mensalidade",
                                                              "periodicity": "MENSAL", "startDate": _dt.date.today().isoformat(), "fixedAmount": "50.00"})
step("solicitação do recebedor recebida (CRIADA/RECEBIDA, allow_retry pelo idRec)", s == 201 and au["status"] == "CRIADA" and au["allowRetry"], au.get("code") or s)
if s == 201:
    s, _, ap = call("POST", "/v1/automatic-authorizations/%s/approval" % au["id"], O, {"payerMaxAmount": "80.00"})
    step("pagador aceita com teto de 80.00 → APROVADA", s == 200 and ap["status"] == "APROVADA", s)
    due = (_dt.date.today() + _dt.timedelta(days=3)).isoformat()
    s, _, ch = call("POST", "/v1/automatic-authorizations/%s/charges" % au["id"], O, {"txid": uuid.uuid4().hex, "dueDate": due, "amount": "50.00", "receiverIspb": "12345678",
                                                                                       "receiverBranch": "0001", "receiverAccount": "998877", "receiverAccountType": "CACC"})
    step("cobrança recorrente registrada (ATIVA, tentativa AGND na data)", s == 201 and ch["status"] == "ATIVA", ch.get("code") or s)
    s, _, bad = call("POST", "/v1/automatic-authorizations/%s/charges" % au["id"], O, {"txid": uuid.uuid4().hex, "dueDate": due, "amount": "90.00", "receiverIspb": "12345678",
                                                                                        "receiverBranch": "0001", "receiverAccount": "998877", "receiverAccountType": "CACC"})
    step("cobrança acima do valor fixo/teto é recusada (422)", s == 422, s)
    s, _, cc = call("POST", "/v1/automatic-authorizations/%s/charges/%s/cancellation" % (au["id"], ch["id"]), O)
    step("pagador cancela a cobrança antes da véspera → CANCELADA", s == 200 and cc["status"] == "CANCELADA", s)
    s, _, ca = call("POST", "/v1/automatic-authorizations/%s/cancellation" % au["id"], O, {"cancelledBy": "PAYER"})
    step("pagador cancela a autorização → CANCELADA (recebedor avisado)", s == 200 and ca["status"] == "CANCELADA", s)

print("4) FALHAS — saldo insuficiente e rejeição do SPI")
r = pay({"payerAccountId": ACC, "method": "KEY", "amount": "99.99", "destination": {"pixKeyType": "EMAIL", "pixKey": "outra@example.com"}}, 201)
st = wait(lambda: call("GET", "/v1/payment-orders/" + r["id"], O)[2]["status"], "REJECTED")
step("valor 99.99 (gatilho do mock): core-ledger recusa (saldo insuficiente) → REJECTED", st == "REJECTED", st)
r2 = pay({"payerAccountId": ACC, "method": "KEY", "amount": "7.00", "destination": {"pixKeyType": "EMAIL", "pixKey": "outra@example.com"}}, 201)
wait(lambda: call("GET", "/v1/payment-orders/" + r2["id"], O)[2]["status"], "RESERVED")
script("simulate-spi.py", "payment-status", r2["endToEndId"], "REJECTED", "--reason", "AC03")
j = call("GET", "/v1/payment-orders/" + r2["id"], O)[2]
step("SPI rejeitou (AC03) → REJECTED com motivo", wait(lambda: call("GET", "/v1/payment-orders/" + r2["id"], O)[2]["status"], "REJECTED") == "REJECTED", j.get("rejectReasonCode"))

print("5) RECEBER UM PIX e DEVOLVER")
out = script("simulate-spi.py", "pix-received", "--amount", "50.00", "--txid", tx)
e2e = [l.split()[1] for l in out.splitlines() if l.startswith("endToEndId:")][0]
got = wait(lambda: call("GET", "/api/pix/v2/pix/" + e2e, P)[0], 200)
step("Pix recebido aparece em GET /api/pix/v2/pix/{e2eid}", got == 200, e2e)
st = wait(lambda: call("GET", "/api/pix/v2/cob/" + tx, P)[2]["status"], "CONCLUIDA")
step("a cobrança foi baixada → CONCLUIDA", st == "CONCLUIDA", st)
s, _, dv = call("PUT", "/api/pix/v2/pix/%s/devolucao/dev%s" % (e2e, int(time.time())), P, {"valor": "10.00", "natureza": "ORIGINAL", "descricao": "demo"})
step("devolução solicitada → EM_PROCESSAMENTO", s == 201 and dv["status"] == "EM_PROCESSAMENTO", dv["status"])
rtr = wait(lambda: (call("GET", "/api/pix/v2/pix/" + e2e, P)[2].get("devolucoes") or [{}])[0].get("rtrId"), None) or \
      (call("GET", "/api/pix/v2/pix/" + e2e, P)[2]["devolucoes"][0]["rtrId"])
script("simulate-spi.py", "refund-status", rtr, "SETTLED", "--e2e", e2e)
st = wait(lambda: call("GET", "/api/pix/v2/pix/%s/devolucao/%s" % (e2e, dv["id"]), P)[2]["status"], "DEVOLVIDO")
step("SPI liquidou a devolução → DEVOLVIDO", st == "DEVOLVIDO", st)

print("6) WEBHOOK — o Pix recebido da etapa 5 chegou ao receptor, assinado")
def last_webhook():
    try:
        w = json.load(urllib.request.urlopen(MOCKS + "/webhook-receiver/last"))
        return w if w["body"]["pix"][0]["endToEndId"] == e2e else None
    except Exception:
        return None


w = None
t0 = time.time()
while time.time() - t0 < 30 and not (w := last_webhook()):      # o job de entrega roda a cada ~2 s
    time.sleep(2)
step("o receptor recebeu o POST /pix do Pix recebido (e2eId, txid, valor, chave)", bool(w) and w["body"]["pix"][0]["txid"] == tx and w["body"]["pix"][0]["valor"] == "50.00"
     and w["body"]["pix"][0]["chave"] == SEED_KEY, w["body"]["pix"][0] if w else "nada recebido")
step("a assinatura x-pix-signature (HMAC-SHA256 de timestamp.corpo) confere e o timestamp é recente", bool(w) and w["signatureValid"] and w["timestampFresh"])
step("o corpo NÃO leva dados do pagador (nome/documento)", bool(w) and "Cliente Pagador" not in json.dumps(w["body"]) and "11144477735" not in json.dumps(w["body"]))
s, _, _ = call("DELETE", "/api/pix/v2/webhook/" + SEED_KEY, P)
step("webhook removido (204)", s == 204, s)

free_keys()
print("\nRESULTADO: %d/%d passos OK" % (sum(ok_all), len(ok_all)))
sys.exit(0 if all(ok_all) else 1)
