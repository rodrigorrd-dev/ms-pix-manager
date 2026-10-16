#!/usr/bin/env python3
"""Simula o spi-gateway (sistema externo): publica no Kafka, em Avro, as mensagens que o SPI mandaria ao ms-pix-manager.

  python3 scripts/simulate-spi.py payment-status <endToEndId> SETTLED            # Pix enviado foi liquidado (pacs.002)
  python3 scripts/simulate-spi.py payment-status <endToEndId> REJECTED --reason AM04
  python3 scripts/simulate-spi.py pix-received --amount 25.00 [--txid <txid>]    # Pix RECEBIDO na conta de seed (pacs.008)
  python3 scripts/simulate-spi.py refund-status <rtrId> SETTLED|REJECTED [--reason AM04]   # resultado da devolução (pacs.004)

Publica em pix.spi.payment-status.v1 / pix.spi.payment-settled.v1 / pix.spi.refund-status.v1 usando o kafka-avro-console-producer do container
`pix-schema-registry` (schema = src/main/resources/avro, registrado no Schema Registry). Requer `docker compose up`.
Só stdlib. O e2eId do pagamento vem de GET /v1/payment-orders/{id} (campo endToEndId).
"""
import argparse, datetime, json, os, random, string, subprocess, sys, uuid

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AVRO = os.path.join(ROOT, "src/main/resources/avro")
now_us = lambda: int(datetime.datetime.now(datetime.timezone.utc).timestamp() * 1_000_000)


def decimal_bytes(value: str) -> str:
    """Decimal Avro (precision 15, scale 2) em JSON: string cujos code points 0-255 são os bytes do inteiro (two's complement)."""
    unscaled = int(round(float(value) * 100))
    raw = unscaled.to_bytes(max(1, (unscaled.bit_length() + 8) // 8), "big", signed=True)
    return "".join(chr(b) for b in raw)


def publish(topic, schema_file, key, record):
    schema = json.load(open(os.path.join(AVRO, schema_file)))
    line = key + "|" + json.dumps(record, ensure_ascii=False)
    cmd = ["docker", "exec", "-i", "pix-schema-registry", "kafka-avro-console-producer", "--bootstrap-server", "kafka:19092",
           "--topic", topic, "--property", "schema.registry.url=http://localhost:8081", "--property", "value.schema=" + json.dumps(schema),
           "--property", "parse.key=true", "--property", "key.separator=|",
           "--property", "key.serializer=org.apache.kafka.common.serialization.StringSerializer"]
    out = subprocess.run(cmd, input=line, text=True, capture_output=True)
    if out.returncode != 0:
        sys.exit("falha ao publicar:\n" + out.stderr[-800:])
    print("publicado em %s (key=%s)" % (topic, key))


def opt(v):
    return None if v is None else {"string": v}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    ps = sub.add_parser("payment-status"); ps.add_argument("e2e"); ps.add_argument("status", choices=["SENT", "SETTLED", "REJECTED"])
    ps.add_argument("--reason", help="ISO 20022 quando REJECTED (AM04 saldo, AC03 conta inválida, AB03 timeout...)")
    rs = sub.add_parser("refund-status"); rs.add_argument("rtr"); rs.add_argument("status", choices=["SETTLED", "REJECTED"])
    rs.add_argument("--reason"); rs.add_argument("--e2e", default="")
    pr = sub.add_parser("pix-received"); pr.add_argument("--amount", required=True); pr.add_argument("--txid")
    pr.add_argument("--e2e"); pr.add_argument("--payer-name", default="Cliente Pagador")
    a = ap.parse_args()
    if a.cmd == "payment-status":
        publish("pix.spi.payment-status.v1", "spi-payment-status.avsc", a.e2e,
                {"messageId": str(uuid.uuid4()), "endToEndId": a.e2e, "status": a.status, "reasonCode": opt(a.reason), "occurredAt": now_us()})
    elif a.cmd == "refund-status":
        publish("pix.spi.refund-status.v1", "spi-refund-status.avsc", a.rtr,
                {"messageId": str(uuid.uuid4()), "rtrId": a.rtr, "endToEndId": a.e2e or a.rtr, "status": a.status, "reasonCode": opt(a.reason), "occurredAt": now_us()})
    else:
        e2e = a.e2e or "E60746948" + datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d%H%M") + "".join(random.choices(string.ascii_lowercase + string.digits, k=11))
        publish("pix.spi.payment-settled.v1", "spi-payment-settled.avsc", e2e, {
            "messageId": str(uuid.uuid4()), "endToEndId": e2e, "txid": opt(a.txid), "amount": decimal_bytes(a.amount), "settledAt": now_us(),
            "receiverIspb": "12345678", "receiverBranch": "0001", "receiverAccount": "100001", "receiverAccountType": "CACC",
            "receiverKeyHash": None, "payerDocumentType": opt("CPF"), "payerDocument": opt("11144477735"), "payerName": opt(a.payer_name),
            "payerIspb": opt("60746948"), "payerBranch": opt("0001"), "payerAccount": opt("987654"), "payerAccountType": opt("CACC"),
            "payerInfo": None, "components": [{"type": "ORIGINAL", "amount": decimal_bytes(a.amount), "agentModality": None,
                                               "withdrawalProviderIspb": None}]})
        print("endToEndId:", e2e)


main()
