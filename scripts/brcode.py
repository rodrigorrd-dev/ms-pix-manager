#!/usr/bin/env python3
"""Gera o BR Code ("Pix copia e cola") que vira QR Code. Só stdlib. O ms só LÊ BR Code (POST /v1/payment-orders/decodings); quem gera é o recebedor.

  python3 scripts/brcode.py static  --key 123e4567-e89b-42d3-a456-426655440000 --name "Fulano de Tal" --city "SAO PAULO" --amount 10.00
  python3 scripts/brcode.py dynamic --url pix.example.com/qr/v2/<location da cobrança> --name "Fulano de Tal" --city "SAO PAULO"
  ... [--qr]   também desenha o QR no terminal (precisa de `npx` + internet, usa o pacote npm `qrcode`) ou grava --png arquivo.png

static  = valor da chave Pix (com ou sem valor fixo), pagável por QR_STATIC. dynamic = aponta p/ o `location` devolvido por POST/PUT /api/pix/v2/cob
(o campo `location` da resposta). O ms paga QR dinâmico: busca o payload JWS em https://<url>, valida assinatura/cadeia e paga o valor da cobrança.
  No dev, o PSP fictício do serviço `mocks` serve /qr/v2/{ok|v-<valor>|paga|expirada|adulterado} em mocks:8443.
"""
import argparse, shutil, subprocess, sys


def f(tag, value):
    return "%s%02d%s" % (tag, len(value), value)


def crc16(data: str) -> str:
    crc = 0xFFFF
    for b in data.encode():
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return "%04X" % crc


def build(a):
    gui = f("00", "br.gov.bcb.pix")
    if a.mode == "static":
        merchant = f("26", gui + f("01", a.key))
        init = "11"                                   # 11 = reutilizável; 12 = uso único
    else:
        merchant = f("26", gui + f("25", a.url))
        init = "12"
    p = f("00", "01") + f("01", init) + merchant + f("52", "0000") + f("53", "986")
    if a.amount:
        p += f("54", "%.2f" % float(a.amount))
    p += f("58", "BR") + f("59", a.name[:25]) + f("60", a.city[:15]) + f("62", f("05", a.txid or "***")) + "6304"
    return p + crc16(p)


ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
ap.add_argument("mode", choices=["static", "dynamic"])
ap.add_argument("--key"); ap.add_argument("--url"); ap.add_argument("--amount"); ap.add_argument("--txid")
ap.add_argument("--name", default="Fulano de Tal"); ap.add_argument("--city", default="SAO PAULO")
ap.add_argument("--qr", action="store_true"); ap.add_argument("--png")
a = ap.parse_args()
if a.mode == "static" and not a.key or a.mode == "dynamic" and not a.url:
    sys.exit("static exige --key; dynamic exige --url")
code = build(a)
print(code)
if (a.qr or a.png) and shutil.which("npx"):
    cmd = ["npx", "--yes", "qrcode@1.5.4", "-o", a.png, code] if a.png else ["npx", "--yes", "qrcode@1.5.4", "-t", "utf8", code]
    subprocess.run(cmd)
elif a.qr or a.png:
    print("(sem npx: use o texto acima em qualquer gerador de QR Code)")
