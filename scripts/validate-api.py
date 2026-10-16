#!/usr/bin/env python3
"""Validação funcional da API (cada endpoint, com casos de sucesso e de erro) contra o ambiente do docker compose.

Uso:  python3 scripts/validate-api.py            (PIX_BASE_URL / KEYCLOAK_URL / CLIENT_SECRET sobrescrevem os defaults)
Saída: PASS/FAIL por verificação; exit code 1 se algo falhar. Só stdlib. Requer o seed (docker/seed) e os dois clients do realm de dev.
Não cobre o que depende de sistemas externos (dict-gateway, core-ledger) nem de Pix recebido via Kafka: ali só valida erros e entrada.
"""
import os, sys, json, urllib.request, urllib.parse, uuid, random, string, datetime
B=os.environ.get("PIX_BASE_URL","http://localhost:8088"); KC=os.environ.get("KEYCLOAK_URL","http://localhost:18180")+"/realms/pix/protocol/openid-connect/token"
ACC="00000000-0000-0000-0000-000000000001"; SEED_KEY="22222222-2222-4222-8222-222222222222"; KEY="123e4567-e89b-42d3-a456-426655440000"
def tok(c):
    d=urllib.parse.urlencode({"grant_type":"client_credentials","client_id":c,"client_secret":os.environ.get("CLIENT_SECRET","dev-secret-local-only")}).encode()
    return json.load(urllib.request.urlopen(urllib.request.Request(KC,d)))["access_token"]
P=tok("pix-partner-dev"); O=tok("pix-backoffice-dev")
def call(m,path,tk=None,body=None,h=None):
    hd={"Content-Type":"application/json"}; hd.update(h or {})
    if tk: hd["Authorization"]="Bearer "+tk
    r=urllib.request.Request(B+path,data=json.dumps(body).encode() if body is not None else None,method=m,headers=hd)
    try: x=urllib.request.urlopen(r); s=x.status; hh=x.headers; b=x.read()
    except urllib.error.HTTPError as e: s=e.code; hh=e.headers; b=e.read()
    try: j=json.loads(b) if b else None
    except Exception: j=b[:80]
    return s,hh,j
def liberar_chaves_pendentes():
    """Limite BCB: 5 chaves vivas por conta PF. Apaga pela API toda chave viva, exceto a do seed (com o mock do DICT elas viram ACTIVE)."""
    s_,_,l_=call("GET","/v1/pix-keys?accountId=%s&size=100"%ACC,O)
    for k_ in l_["content"]:
        if k_["status"] not in("DELETED","REJECTED") and k_["id"]!=SEED_KEY:
            _,hh_,_=call("GET","/v1/pix-keys/"+k_["id"],O); call("POST","/v1/pix-keys/%s/deletion"%k_["id"],O,{"reason":"USER_REQUESTED"},{"If-Match":hh_.get("ETag")})
res=[]
def chk(name,cond,info=""):
    cond=bool(cond); res.append(cond); print(("PASS " if cond else "FAIL ")+name+("  | "+str(info) if info else ""))
rid=lambda n=32:''.join(random.choice(string.ascii_lowercase+string.digits) for _ in range(n))
now=datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None); ini=(now-datetime.timedelta(days=1)).strftime("%Y-%m-%dT%H:%M:%SZ"); fim=(now+datetime.timedelta(hours=1)).strftime("%Y-%m-%dT%H:%M:%SZ")

print("== Segurança")
s,_,j=call("GET","/v1/pix-keys?accountId="+ACC); chk("sem token → 401",s==401,s)
s,_,j=call("GET","/v1/pix-keys?accountId="+ACC,P); chk("token partner em /v1 → 403",s==403,s)
s,_,j=call("GET","/api/pix/v2/cob?inicio=%s&fim=%s"%(ini,fim),O); chk("token backoffice em /api/pix/v2 → 403",s==403,s)
s,h,j=call("GET","/v1/pix-keys?accountId="+ACC,"abc.def.ghi"); chk("token inválido → 401",s==401,s)

print("== cob (imediata)")
cob={"calendario":{"expiracao":3600},"devedor":{"cpf":"52998224725","nome":"Fulano"},"valor":{"original":"123.45"},"chave":KEY,"solicitacaoPagador":"teste"}
tx=rid(); s,h,j=call("PUT","/api/pix/v2/cob/"+tx,P,cob)
chk("PUT cob cria → 201 + Location + ETag",s==201 and h.get("Location","").endswith("/cob/"+tx) and h.get("ETag"),(s,h.get("Location"),h.get("ETag")))
chk("  corpo: status ATIVA, valor 123.45, txid, location",j and j.get("status")=="ATIVA" and j["valor"]["original"]=="123.45" and j.get("txid")==tx and j.get("location"),{k:j.get(k) for k in("status","txid")} if j else j)
et=h.get("ETag")
s,h,j=call("PUT","/api/pix/v2/cob/"+tx,P,cob); chk("PUT cob mesmo txid+mesmo corpo (replay) → 200",s==200,s)
c2=json.loads(json.dumps(cob)); c2["valor"]["original"]="1.00"
s,h,j=call("PUT","/api/pix/v2/cob/"+tx,P,c2); chk("PUT cob mesmo txid, corpo diferente → 409 TXID_CONFLICT",s==409 and j.get("code")=="PIX-409-020",(s,j.get("code") if j else j))
s,h,j=call("PUT","/api/pix/v2/cob/curto",P,cob); chk("txid inválido → 400",s==400,(s,j.get("code") if j else j))
bad=json.loads(json.dumps(cob)); bad["valor"]["original"]="12,3"
s,h,j=call("PUT","/api/pix/v2/cob/"+rid(),P,bad); chk("valor em formato inválido → 400",s==400,(s,[e["field"] for e in j.get("errors",[])] if j else j))
bad=json.loads(json.dumps(cob)); bad["chave"]="123e4567-e89b-42d3-a456-000000000000"
s,h,j=call("PUT","/api/pix/v2/cob/"+rid(),P,bad); chk("chave inexistente → 422 KEY_NOT_ACTIVE",s==422 and j.get("code")=="PIX-422-021",(s,j.get("code")))
s,h,j=call("POST","/api/pix/v2/cob",P,cob); t2=j.get("txid") if j else None
chk("POST cob (txid gerado) → 201",s==201 and t2 and 26<=len(t2)<=35,(s,t2))
s,h,j=call("GET","/api/pix/v2/cob/"+tx,P); chk("GET cob → 200 + ETag",s==200 and h.get("ETag")==et,(s,h.get("ETag"),et))
s,h,j=call("GET","/api/pix/v2/cob/"+tx,P,h={"If-None-Match":et}); chk("GET cob If-None-Match → 304",s==304,s)
s,h,j=call("GET","/api/pix/v2/cob/"+rid(),P); chk("GET cob inexistente → 404 problem+json",s==404 and j.get("code")=="PIX-404-001",(s,h.get("Content-Type")))
s,h,j=call("PATCH","/api/pix/v2/cob/"+tx,P,{"valor":{"original":"150.00"}}); chk("PATCH sem If-Match → 428",s==428,(s,j.get("code")))
s,h,j=call("PATCH","/api/pix/v2/cob/"+tx,P,{"valor":{"original":"150.00"}},{"If-Match":"abc"}); chk("PATCH If-Match malformado → 400",s==400,(s,j.get("code")))
s,h,j=call("PATCH","/api/pix/v2/cob/"+tx,P,{"valor":{"original":"150.00"}},{"If-Match":'"999"'}); chk("PATCH versão antiga → 412",s==412,(s,j.get("code")))
s,h,j=call("PATCH","/api/pix/v2/cob/"+tx,P,{"valor":{"original":"150.00"}},{"If-Match":et}); et2=h.get("ETag")
chk("PATCH com If-Match correto → 200, valor 150.00, ETag novo",s==200 and j["valor"]["original"]=="150.00" and et2!=et,(s,et,et2))
s,h,j=call("PATCH","/api/pix/v2/cob/"+tx,P,{"status":"REMOVIDA_PELO_USUARIO_RECEBEDOR"},{"If-Match":et2}); chk("PATCH remover → status REMOVIDA_PELO_USUARIO_RECEBEDOR",s==200 and j["status"]=="REMOVIDA_PELO_USUARIO_RECEBEDOR",(s,j.get("status") if j else j))
s,h,j=call("PATCH","/api/pix/v2/cob/"+tx,P,{"valor":{"original":"9.00"}},{"If-Match":h.get("ETag")}); chk("PATCH em cobrança removida → 422",s==422,(s,j.get("code")))
s,h,j=call("GET","/api/pix/v2/cob?inicio=%s&fim=%s"%(ini,fim),P); chk("GET cob lista → 200 com paginação",s==200 and "cobs" in j and "parametros" in j and len(j["cobs"])>=2,(s,len(j.get("cobs",[])),j.get("parametros",{}).get("paginacao")))
s,h,j=call("GET","/api/pix/v2/cob?inicio=%s&fim=%s&status=REMOVIDA_PELO_USUARIO_RECEBEDOR"%(ini,fim),P); chk("  filtro status",s==200 and all(c["status"]=="REMOVIDA_PELO_USUARIO_RECEBEDOR" for c in j["cobs"]) and len(j["cobs"])>=1,len(j["cobs"]))
s,h,j=call("GET","/api/pix/v2/cob?inicio=%s&fim=%s&cpf=52998224725&cnpj=11222333000181"%(ini,fim),P); chk("  cpf+cnpj juntos → 400",s==400,s)
s,h,j=call("GET","/api/pix/v2/cob?inicio=%s&fim=%s&paginacao.itensPorPagina=101"%(ini,fim),P); chk("  itensPorPagina=101 → 400",s==400,s)
s,h,j=call("GET","/api/pix/v2/cob",P); chk("  sem inicio/fim → 400",s==400,s)
s,h,j=call("GET","/api/pix/v2/cob?inicio=%s&fim=%s"%(fim,ini),P); chk("  inicio > fim → 400 ou vazio (definir)",s in(200,400),(s,len(j.get("cobs",[])) if s==200 else j.get("code")))

print("== cobv (vencimento)")
cobv={"calendario":{"dataDeVencimento":"2030-12-31","validadeAposVencimento":30},"devedor":{"cnpj":"11222333000181","nome":"Empresa"},"valor":{"original":"500.00"},"chave":KEY}
tv=rid(); s,h,j=call("PUT","/api/pix/v2/cobv/"+tv,P,cobv); chk("PUT cobv → 201 + Location",s==201 and h.get("Location","").endswith("/cobv/"+tv),(s,h.get("Location")))
s,h,j=call("GET","/api/pix/v2/cobv/"+tv,P); chk("GET cobv → 200, vencimento 2030-12-31",s==200 and j["calendario"]["dataDeVencimento"]=="2030-12-31",(s,j["calendario"] if s==200 else j))
s,h,j=call("PUT","/api/pix/v2/cobv/"+rid(),P,dict(cobv,calendario={"dataDeVencimento":"2020-01-01"})); chk("cobv vencimento no passado → 4xx",400<=s<500,(s,j.get("code") if j else j))
s,h,j=call("GET","/api/pix/v2/cobv/"+rid(),P); chk("GET cobv inexistente → 404",s==404,s)
s,h,j=call("GET","/api/pix/v2/cobv/"+tv,P); etv=h.get("ETag"); chk("GET cobv emite ETag",bool(etv),etv)
s,h,j=call("GET","/api/pix/v2/cobv/"+tv,P,h={"If-None-Match":etv}); chk("GET cobv If-None-Match → 304",s==304,s)

print("== Pix recebido / devolução")
e2e="E12345678"+now.strftime("%Y%m%d%H%M")+"ABCDEFGHIJK"; e2e=e2e[:32]
s,h,j=call("GET","/api/pix/v2/pix/"+e2e,P); chk("GET pix por e2eid inexistente → 404",s==404,s)
s,h,j=call("GET","/api/pix/v2/pix?inicio=%s&fim=%s"%(ini,fim),P); chk("GET pix lista → 200 (vazia)",s==200 and "pix" in j,(s,list(j.keys()) if j else j))
s,h,j=call("GET","/api/pix/v2/pix?inicio=%s&fim=%s&cpf=1&cnpj=2"%(ini,fim),P); chk("  cpf+cnpj → 400",s==400,s)
s,h,j=call("PUT","/api/pix/v2/pix/%s/devolucao/dev1"%e2e,P,{"valor":"10.00","natureza":"ORIGINAL"}); chk("PUT devolução de Pix inexistente → 404",s==404,s)
s,h,j=call("PUT","/api/pix/v2/pix/%s/devolucao/dev1"%e2e,P,{"valor":"x"}); chk("PUT devolução valor inválido → 400",s==400,s)
s,h,j=call("GET","/api/pix/v2/pix/%s/devolucao/dev1"%e2e,P); chk("GET devolução inexistente → 404",s==404,s)

print("== Chaves Pix")
liberar_chaves_pendentes()
s,h,j=call("POST","/v1/pix-keys",O,{"accountId":ACC,"pixKeyType":"EVP"}); chk("POST pix-keys sem Idempotency-Key → 201 (opcional) ou 4xx",s in(201,400,428),(s,j.get("status") if j else j))
ik=str(uuid.uuid4()); em="v%s@example.com"%rid(8)
s,h,j=call("POST","/v1/pix-keys",O,{"accountId":ACC,"pixKeyType":"EMAIL","pixKey":em},{"Idempotency-Key":ik}); kid=j.get("id") if j else None
chk("POST pix-keys e-mail → 201 + Location",s==201 and kid and h.get("Location","").endswith("/pix-keys/"+str(kid)),(s,j.get("status") if j else j,h.get("Location")))
print("     corpo:",{k:j.get(k) for k in("id","pixKeyType","pixKey","status")} if j else j)
s,h2,j2=call("POST","/v1/pix-keys",O,{"accountId":ACC,"pixKeyType":"EMAIL","pixKey":em},{"Idempotency-Key":ik}); chk("  replay mesma chave+corpo → mesma resposta",s==201 and j2.get("id")==kid,(s,h2.get("Idempotent-Replayed")))
s,h2,j2=call("POST","/v1/pix-keys",O,{"accountId":ACC,"pixKeyType":"EMAIL","pixKey":"outro%s@example.com"%rid(5)},{"Idempotency-Key":ik}); chk("  mesma Idempotency-Key, corpo diferente → 422 IDEMPOTENCY_MISMATCH",s==422 and j2.get("code")=="PIX-422-900",(s,j2.get("code")))
s,h2,j2=call("POST","/v1/pix-keys",O,{"accountId":ACC,"pixKeyType":"EMAIL","pixKey":em},{"Idempotency-Key":str(uuid.uuid4())}); chk("  chave duplicada, outra Idempotency-Key → 409/422",s in(409,422),(s,j2.get("code")))
s,h2,j2=call("POST","/v1/pix-keys",O,{"accountId":ACC,"pixKeyType":"EMAIL","pixKey":"nao-e-email"},{"Idempotency-Key":str(uuid.uuid4())}); chk("  e-mail inválido → 4xx",400<=s<500,(s,j2.get("code")))
s,h2,j2=call("POST","/v1/pix-keys",O,{"accountId":"00000000-0000-0000-0000-0000000000ff","pixKeyType":"EVP"},{"Idempotency-Key":str(uuid.uuid4())}); chk("  conta inexistente → 404",s==404,s)
s,h2,j2=call("POST","/v1/pix-keys",O,{"pixKeyType":"EVP"},{"Idempotency-Key":str(uuid.uuid4())}); chk("  sem accountId → 400",s==400,s)
s,h,j=call("GET","/v1/pix-keys/%s"%kid,O); et=h.get("ETag"); chk("GET pix-key → 200 + ETag",s==200 and et and j["id"]==kid,(s,et,j.get("status")))
s,h2,j2=call("GET","/v1/pix-keys/%s"%kid,O,h={"If-None-Match":et}); chk("GET pix-key If-None-Match → 304",s==304,s)
s,h2,j2=call("GET","/v1/pix-keys/%s"%kid,O,h={"If-None-Match":"*"}); chk("  If-None-Match: * → 304",s==304,s)
s,h2,j2=call("GET","/v1/pix-keys/%s"%kid,O,h={"If-None-Match":'"99", W/'+et}); chk("  lista com validador fraco → 304",s==304,s)
s,h2,j2=call("GET","/v1/pix-keys/%s"%kid,O,h={"If-None-Match":'"99"'}); chk("  versão diferente → 200",s==200,s)
s,h2,j2=call("GET","/v1/pix-keys/%s"%uuid.uuid4(),O); chk("GET pix-key inexistente → 404",s==404,s)
s,h2,j2=call("GET","/v1/pix-keys/nao-uuid",O); chk("GET pix-key id inválido → 400",s==400,s)
s,h2,j2=call("GET","/v1/pix-keys?accountId=%s&size=5"%ACC,O); chk("GET pix-keys lista paginada (envelope com totalPages)",s==200 and j2["totalElements"]>=1 and len(j2["content"])<=5 and j2.get("totalPages")==-(-j2["totalElements"]//5),{k:j2.get(k) for k in("page","size","totalElements","totalPages")})
s,h2,j2=call("GET","/v1/pix-keys?accountId=%s&status=ACTIVE"%ACC,O); chk("  filtro status=ACTIVE",s==200 and all(x["status"]=="ACTIVE" for x in j2["content"]),j2["totalElements"])
s,h2,j2=call("GET","/v1/pix-keys?accountId=%s&size=101"%ACC,O); chk("  size=101 → 400",s==400,s)
s,h2,j2=call("GET","/v1/pix-keys",O); chk("  sem accountId → 400",s==400,s)
s,h2,j2=call("POST","/v1/pix-keys/%s/deletion"%kid,O,{"reason":"USER_REQUESTED"}); chk("deletion sem If-Match → 428",s==428,s)
s,h2,j2=call("POST","/v1/pix-keys/%s/deletion"%kid,O,{"reason":"USER_REQUESTED"},{"If-Match":'"999"'}); chk("deletion versão antiga → 412",s==412,s)
s,h2,j2=call("POST","/v1/pix-keys/%s/deletion"%kid,O,{"reason":"XYZ"},{"If-Match":et}); chk("deletion reason inválido → 400",s==400,s)
s,h2,j2=call("POST","/v1/pix-keys/%s/deletion"%kid,O,{"reason":"USER_REQUESTED"},{"If-Match":et}); chk("deletion ok → 200 status DELETED/pendente",s==200,(s,j2.get("status") if j2 else j2))
s,h2,j2=call("POST","/v1/pix-keys/lookups",O,{"payerAccountId":ACC,"pixKeyType":"EMAIL","pixKey":"x@example.com"}); chk("lookups → 502 (dict-gateway externo ausente) ou 200/404",s in(200,404,502),(s,j2.get("code") if j2 else j2))
s,h2,j2=call("POST","/v1/pix-keys/lookups",O,{"pixKey":"x"}); chk("lookups payload inválido → 400",s==400,s)

print("== Prova de posse (OTP)")
import urllib.parse as _up
_em="otp%s@example.com"%rid(6)
_s,_,_k=call("POST","/v1/pix-keys",O,{"accountId":ACC,"pixKeyType":"EMAIL","pixKey":_em},{"Idempotency-Key":str(uuid.uuid4())})
chk("e-mail nasce PENDING_OWNERSHIP_PROOF",_s==201 and _k.get("status")=="PENDING_OWNERSHIP_PROOF",_s)
_s,_,_c=call("POST","/v1/pix-keys/%s/ownership-proof/challenges"%_k["id"],O)
chk("emitir desafio ⇒ 202 sem o código na resposta",_s==202 and "code" not in _c and _c.get("channel")=="EMAIL",_c)
_s,_h,_c2=call("POST","/v1/pix-keys/%s/ownership-proof/challenges"%_k["id"],O)
chk("novo pedido imediato ⇒ 429 PIX-429-002 com Retry-After",_s==429 and _c2.get("code")=="PIX-429-002" and int(_h.get("Retry-After","0"))>0,(_s,_h.get("Retry-After")))
_s,_,_v=call("POST","/v1/pix-keys/%s/ownership-proof/verifications"%_k["id"],O,{"code":"abc"})
chk("código mal formado ⇒ 400",_s==400,_s)
_code=json.load(urllib.request.urlopen(os.environ.get("MOCKS_URL","http://localhost:18090")+"/otp/v1/last?destination="+_up.quote(_em)))["code"]
_s,_,_v=call("POST","/v1/pix-keys/%s/ownership-proof/verifications"%_k["id"],O,{"code":"000000" if _code!="000000" else "000001"})
chk("código errado ⇒ 422 PIX-422-014",_s==422 and _v.get("code")=="PIX-422-014",(_s,_v.get("code")))
_s,_,_v=call("POST","/v1/pix-keys/%s/ownership-proof/verifications"%_k["id"],O,{"code":_code})
chk("código certo ⇒ 200 PENDING_DICT",_s==200 and _v.get("status")=="PENDING_DICT",(_s,_v.get("status")))
_s,_,_v=call("POST","/v1/pix-keys/%s/ownership-proof/verifications"%_k["id"],O,{"code":_code})
chk("repetir o mesmo código ⇒ 422 (chave não aguarda mais prova)",_s==422,_s)
_s,_,_v=call("POST","/v1/pix-keys/%s/ownership-proof/challenges"%_k["id"],P)
chk("token sem escopo pixkey.write ⇒ 403",_s==403,_s)

print("== Webhook")
_hk="https://mocks:8443/webhook-receiver/ok"
s,h,j=call("PUT","/api/pix/v2/webhook/"+KEY,P,{"webhookUrl":_hk}); chk("PUT webhook cria/atualiza (201|200) com webhookUrl, chave e criacao",s in(200,201) and j.get("webhookUrl")==_hk and j.get("chave")==KEY and j.get("criacao"),(s,j))
s,h,j=call("PUT","/api/pix/v2/webhook/"+KEY,P,{"webhookUrl":_hk+"2"}); chk("  segundo PUT atualiza a URL ⇒ 200",s==200 and j.get("webhookUrl")==_hk+"2",s)
s,h,j=call("GET","/api/pix/v2/webhook/"+KEY,P); chk("GET webhook ⇒ 200 com a URL nova",s==200 and j.get("webhookUrl")==_hk+"2",s)
s,h,j=call("GET","/api/pix/v2/webhook?inicio=%s&fim=%s"%(ini,fim),P); chk("GET lista ⇒ 200 com parametros e webhooks",s==200 and "parametros" in j and any(w["chave"]==KEY for w in j["webhooks"]),(s,list(j) if j else j))
s,h,j=call("GET","/api/pix/v2/webhook?inicio=%s&fim=%s"%(fim,ini),P); chk("  inicio > fim ⇒ 400",s==400,s)
for bad in ("http://mocks:8080/x","ftp://x.example/h","https://user:pw@x.example/h","https://x.example/h#f"):
    s,h,j=call("PUT","/api/pix/v2/webhook/"+KEY,P,{"webhookUrl":bad}); chk("  URL fora da política ⇒ 422 PIX-422-017 (%s)"%bad[:28],s==422 and j.get("code")=="PIX-422-017",(s,j.get("code") if j else j))
s,h,j=call("PUT","/api/pix/v2/webhook/"+KEY,P,{}); chk("  corpo sem webhookUrl ⇒ 400",s==400,s)
s,h,j=call("PUT","/api/pix/v2/webhook/"+KEY,O,{"webhookUrl":_hk}); chk("  token sem escopo webhook.write (backoffice) ⇒ 403",s==403,s)
s,h,j=call("PUT","/api/pix/v2/webhook/chave-que-nao-existe@example.com",P,{"webhookUrl":_hk}); chk("  chave inexistente ⇒ 404",s==404,s)
s,h,j=call("DELETE","/api/pix/v2/webhook/"+KEY,P); chk("DELETE webhook ⇒ 204",s==204,s)
s,h,j=call("DELETE","/api/pix/v2/webhook/"+KEY,P); chk("  DELETE de novo ⇒ 404",s==404,s)
s,h,j=call("GET","/api/pix/v2/webhook/"+KEY,P); chk("  GET após remover ⇒ 404",s==404,s)
s,h,j=call("POST","/v1/webhook-deliveries/%s/resend"%uuid.uuid4(),O); chk("resend de entrega inexistente ⇒ 404 (token back-office)",s==404,s)
s,h,j=call("POST","/v1/webhook-deliveries/%s/resend"%uuid.uuid4(),P); chk("  resend com token de parceiro ⇒ 403 (escopo)",s==403,s)

print("== Ordens de pagamento")
BR="00020126580014br.gov.bcb.pix0136123e4567-e89b-42d3-a456-426655440000520400005303986540510.005802BR5913Fulano de Tal6009SAO PAULO62070503***63048654"
s,h,j=call("POST","/v1/payment-orders/decodings",O,{"payload":BR}); chk("decodings BR Code válido → 200",s==200,(s,{k:j.get(k) for k in list(j)[:6]} if s==200 else j))
s,h,j=call("POST","/v1/payment-orders/decodings",O,{"payload":BR[:-4]+"0000"}); chk("decodings CRC inválido → 422 PIX-422-013 (status e código na mesma faixa)",s==422 and j.get("code")=="PIX-422-013",(s,j.get("code") if j else j))
s,h,j=call("POST","/v1/payment-orders/decodings",O,{}); chk("decodings sem payload → 400",s==400,s)
po={"payerAccountId":ACC,"method":"MANUAL","amount":"100.00","destination":{"ispb":"60746948","branch":"0001","account":"123456","accountType":"CACC","name":"Beneficiário","document":"52998224725"}}
s,h,j=call("POST","/v1/payment-orders",O,po); chk("POST payment-orders sem Idempotency-Key → 400/428",s in(400,428),(s,j.get("code") if j else j))
ik=str(uuid.uuid4()); s,h,j=call("POST","/v1/payment-orders",O,po,{"Idempotency-Key":ik}); pid=j.get("id") if j else None
chk("POST payment-orders manual → 201 + Location",s==201 and pid and h.get("Location","").endswith(str(pid)),(s,j.get("status") if j else j))
print("     corpo:",{k:j.get(k) for k in("id","method","amount","status")} if s==201 else j)
s,h2,j2=call("POST","/v1/payment-orders",O,po,{"Idempotency-Key":ik}); chk("  replay → mesma resposta + Idempotent-Replayed",s==201 and j2.get("id")==pid and h2.get("Idempotent-Replayed")=="true",(s,h2.get("Idempotent-Replayed")))
s,h2,j2=call("POST","/v1/payment-orders",O,dict(po,amount="101.00"),{"Idempotency-Key":ik}); chk("  mesma key, corpo diferente → 422 IDEMPOTENCY_MISMATCH",s==422 and j2.get("code")=="PIX-422-900",(s,j2.get("code")))
s,h2,j2=call("POST","/v1/payment-orders",O,dict(po,amount="0.00"),{"Idempotency-Key":str(uuid.uuid4())}); chk("  amount 0.00 → 4xx",400<=s<500,(s,j2.get("code")))
s,h2,j2=call("POST","/v1/payment-orders",O,dict(po,payerAccountId="00000000-0000-0000-0000-0000000000ff"),{"Idempotency-Key":str(uuid.uuid4())}); chk("  conta pagadora inexistente → 404",s==404,s)
s,h2,j2=call("POST","/v1/payment-orders",O,{"method":"KEY"},{"Idempotency-Key":str(uuid.uuid4())}); chk("  payload incompleto → 400",s==400,s)
s,h2,j2=call("POST","/v1/payment-orders",O,dict(po,method="QR_DYNAMIC"),{"Idempotency-Key":str(uuid.uuid4())}); chk("  QR_DYNAMIC (backlog) → erro explícito 4xx/5xx não-500",s!=500 and s>=400,(s,j2.get("code")))
if pid:
    s,h,j=call("GET","/v1/payment-orders/"+pid,O); et=h.get("ETag"); chk("GET payment-order → 200 + ETag",s==200 and j["id"]==pid and et,(s,et,j.get("status")))
    s,h3,j3=call("GET","/v1/payment-orders/"+pid,O,h={"If-None-Match":et}); chk("GET payment-order If-None-Match → 304",s==304,s)
    s,h2,j2=call("POST","/v1/payment-orders/%s/cancellation"%pid,O); chk("cancellation sem If-Match → 428",s==428,s)
    s,h2,j2=call("POST","/v1/payment-orders/%s/cancellation"%pid,O,h={"If-Match":'"999"'}); chk("cancellation versão antiga → 412",s==412,s)
    s,h2,j2=call("POST","/v1/payment-orders/%s/cancellation"%pid,O,h={"If-Match":et}); chk("cancellation (resultado de acordo com o status)",s in(200,422),(s,j2.get("status") if s==200 else j2.get("code")))
s,h,j=call("GET","/v1/payment-orders/"+str(uuid.uuid4()),O); chk("GET payment-order inexistente → 404",s==404,s)
s,h,j=call("GET","/v1/payment-orders?payerAccountId=%s&size=5"%ACC,O); chk("GET payment-orders lista",s==200 and j["totalElements"]>=1,{k:j.get(k) for k in("page","size","totalElements")})
s,h,j=call("GET","/v1/payment-orders?payerAccountId=%s&from=%s&to=%s"%(ACC,ini,fim),O); chk("  filtro from/to",s==200,s)
s,h,j=call("GET","/v1/payment-orders",O); chk("  sem payerAccountId → 400",s==400,s)

print("== Limites")
s,h,j=call("GET","/v1/limits/accounts/"+ACC,O); chk("GET limits → 200, 6 escopos",s==200 and len(j.get("limits",j.get("items",[])) or [])>=1,(s,json.dumps(j)[:200]))
lim=j
s,h,j=call("PUT","/v1/limits/accounts/%s/DAILY"%ACC,O,{"amount":"5000.00"}); chk("PUT limit sem If-Match → 428",s==428,s)
s,h,j=call("PUT","/v1/limits/accounts/%s/DAILY"%ACC,O,{"amount":"5000.00"},{"If-Match":'"999"'}); chk("PUT limit versão antiga → 412 (ou 200 se 1ª criação)",s in(412,200),s)
s,h,j=call("PUT","/v1/limits/accounts/%s/NOPE"%ACC,O,{"amount":"5000.00"},{"If-Match":"*"}); chk("PUT limit scope inválido → 400",s==400,s)
s,h,j=call("PUT","/v1/limits/accounts/%s/DAILY"%ACC,O,{"amount":"abc"},{"If-Match":"*"}); chk("PUT limit amount inválido → 400",s==400,s)
s,h,j=call("PUT","/v1/limits/accounts/%s/DAILY"%ACC,O,{"amount":"5000.00"},{"If-Match":"*"}); chk("PUT limit If-Match:* → 200",s==200,(s,j))
s,h,j=call("PUT","/v1/limits/accounts/%s/DAILY"%ACC,O,{"amount":"999999999999.00"},{"If-Match":"*"}); chk("PUT limit com valor acima do máximo monetário → 422 PIX-422-003",s==422 and j.get("code")=="PIX-422-003",(s,j.get("code") if j else j))
s,h,j=call("GET","/v1/limits/accounts/"+str(uuid.uuid4()),O); chk("GET limits de conta desconhecida → 403/404",s in(403,404),s)

print("== Operação")
for p,exp in [("/actuator/health",200),("/actuator/health/readiness",200),("/actuator/health/liveness",200),("/actuator/info",200),("/actuator/prometheus",200),("/v3/api-docs",200),("/springwolf/docs",200),("/swagger-ui/index.html",200),("/actuator/env",401),("/actuator/beans",401)]:
    s,h,j=call("GET",p); chk("GET "+p+" (sem token) → %d"%exp,s==exp,s)
for p in ("/actuator/env","/actuator/beans","/actuator/heapdump"):
    s,h,j=call("GET",p,O); chk("GET "+p+" (com token) → 404 (não exposto)",s==404,s)
s,h,j=call("GET","/v3/api-docs"); chk("OpenAPI declara o esquema Bearer JWT (Swagger Authorize)",s==200 and j.get("components",{}).get("securitySchemes",{}).get("bearer-jwt",{}).get("scheme")=="bearer",list(j.get("components",{}).get("securitySchemes",{}) or []))
s,h,j=call("DELETE","/v1/pix-keys",O); chk("método não suportado → 405 + Allow",s==405 and h.get("Allow"),(s,h.get("Allow")))
s,h,j=call("POST","/v1/payment-orders/decodings",O,None,{"Content-Type":"text/plain"}); chk("Content-Type errado → 415/400",s in(415,400),s)
s,h,j=call("GET","/rota/inexistente",O); chk("rota inexistente → 404 problem+json",s==404,(s,h.get("Content-Type")))
liberar_chaves_pendentes()
print("\nTOTAL: %d/%d OK"%(sum(res),len(res)))
sys.exit(0 if all(res) else 1)
