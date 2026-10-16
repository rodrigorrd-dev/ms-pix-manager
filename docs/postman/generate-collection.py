#!/usr/bin/env python3
"""Gera ms-pix-manager.postman_collection.json e o ambiente local. Uso: python3 docs/postman/generate-collection.py
Edite este arquivo (não os JSON) para mudar a collection e rode de novo."""
import json, uuid
import os
OUT=os.path.dirname(os.path.abspath(__file__))+'/'
def url(raw, query=None):
    base, _, qs = raw.partition('?')
    path = base.split('}}',1)[1].lstrip('/') if base.startswith('{{') else base
    host = base.split('}}',1)[0]+'}}'
    u={"raw":raw,"host":[host],"path":[p for p in path.split('/') if p]}
    if qs:
        u["query"]=[{"key":k.split('=')[0],"value":k.split('=',1)[1],**({"disabled":True} if k.startswith('~') else {})} for k in qs.split('&')]
        for q in u["query"]:
            if q["key"].startswith('~'): q["key"]=q["key"][1:]
    return u
def ev(kind, lines): return {"listen":kind,"script":{"type":"text/javascript","exec":lines}}
def req(name, method, raw, body=None, headers=None, pre=None, test=None, auth=None, desc=None, form=None):
    r={"method":method,"header":[{"key":k,"value":v} for k,v in (headers or {}).items()],"url":url(raw)}
    if body is not None:
        r["header"].append({"key":"Content-Type","value":"application/json"})
        r["body"]={"mode":"raw","raw":json.dumps(body,indent=2,ensure_ascii=False),"options":{"raw":{"language":"json"}}}
    if form is not None:
        r["body"]={"mode":"urlencoded","urlencoded":[{"key":k,"value":v} for k,v in form.items()]}
    if auth=="none": r["auth"]={"type":"noauth"}
    if desc: r["description"]=desc
    it={"name":name,"request":r,"event":[]}
    if pre: it["event"].append(ev("prerequest",pre))
    if test: it["event"].append(ev("test",test))
    if not it["event"]: del it["event"]
    return it
def folder(name, items, auth=None, desc=None):
    f={"name":name,"item":items}
    if auth: f["auth"]={"type":"bearer","bearer":[{"key":"token","value":"{{%s}}"%auth,"type":"string"}]}
    if desc: f["description"]=desc
    return f
ETAG_SAVE=["const e = pm.response.headers.get('ETag'); if (e) pm.collectionVariables.set('etag', e);"]
def status_test(*codes): return ["pm.test('status %s', () => pm.expect(pm.response.code).to.be.oneOf([%s]));"%("/".join(map(str,codes)),",".join(map(str,codes)))]
GEN_TXID=["const c='abcdefghijklmnopqrstuvwxyz0123456789'; let t=''; for(let i=0;i<32;i++) t+=c[Math.floor(Math.random()*c.length)]; pm.collectionVariables.set('txid', t);"]
GEN_E2E=["const c='ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'; let t='E'+pm.variables.get('ispb'); const d=new Date().toISOString().replace(/[-:T]/g,'').slice(0,12); t+=d; while(t.length<32) t+=c[Math.floor(Math.random()*c.length)]; pm.collectionVariables.set('e2eid', t);"]
UUID_PRE=lambda var:["if(!pm.collectionVariables.get('%s')) pm.collectionVariables.set('%s', require('uuid').v4());"%(var,var)]
IDEMP=["pm.collectionVariables.set('idempotency_key', require('uuid').v4());"]

CLEANUP=["const base = pm.variables.get('base_url'); const tk = pm.collectionVariables.get('token_backoffice');",
"const h = { Authorization: 'Bearer ' + tk, 'Content-Type': 'application/json' };",
"(pm.response.json().content || []).filter(k => !['DELETED','REJECTED'].includes(k.status) && k.id !== '22222222-2222-4222-8222-222222222222').forEach(k => {",
"  pm.sendRequest({ url: `${base}/v1/pix-keys/${k.id}`, method: 'GET', header: h }, (e, g) => {",
"    if (e) return;",
"    pm.sendRequest({ url: `${base}/v1/pix-keys/${k.id}/deletion`, method: 'POST', header: Object.assign({'If-Match': g.headers.get('ETag')}, h),",
"      body: { mode: 'raw', raw: JSON.stringify({ reason: 'USER_REQUESTED' }) } }, () => {});",
"  });",
"});"]
# ---------------- Keycloak ----------------
def token_req(label, cid, secvar, tokvar):
    return req(f"Token — {label}", "POST", "{{keycloak_url}}/realms/{{realm}}/protocol/openid-connect/token",
        form={"grant_type":"client_credentials","client_id":"{{%s}}"%cid,"client_secret":"{{%s}}"%secvar},
        auth="none",
        test=["const j = pm.response.json();","pm.test('token emitido', () => pm.expect(j.access_token).to.be.a('string'));",
              f"pm.collectionVariables.set('{tokvar}', j.access_token);",
              f"pm.collectionVariables.set('{tokvar}_exp', Date.now() + (j.expires_in - 15) * 1000);"],
        desc=f"client_credentials do client {label}. O token fica em {{{{{tokvar}}}}} e é renovado sozinho pelo pre-request da collection.")
keycloak=folder("Keycloak (autenticação)",[
    token_req("pix-partner-dev (escopos BCB: cob/cobv/pix/webhook/payloadlocation)","client_id_partner","client_secret","token_partner"),
    token_req("pix-backoffice-dev (gestão /v1: chaves, pagamentos, limites)","client_id_backoffice","client_secret","token_backoffice"),
    req("OpenID configuration","GET","{{keycloak_url}}/realms/{{realm}}/.well-known/openid-configuration",auth="none"),
    req("JWKS (chaves públicas)","GET","{{keycloak_url}}/realms/{{realm}}/protocol/openid-connect/certs",auth="none"),
    req("Admin — token (master realm)","POST","{{keycloak_url}}/realms/master/protocol/openid-connect/token",
        form={"grant_type":"password","client_id":"admin-cli","username":"{{keycloak_admin_user}}","password":"{{keycloak_admin_password}}"},auth="none",
        test=["pm.collectionVariables.set('keycloak_admin_token', pm.response.json().access_token);"]),
    req("Admin — listar clients do realm","GET","{{keycloak_url}}/admin/realms/{{realm}}/clients",
        auth="none",headers={"Authorization":"Bearer {{keycloak_admin_token}}"},desc="Rode antes 'Admin — token (master realm)'."),
],desc="Todos os tokens são client_credentials do realm `pix`. Não precisa rodar na mão: o pre-request da collection busca/renova token_partner e token_backoffice.")

# ---------------- BCB ----------------
cob_body={"calendario":{"expiracao":3600},"devedor":{"cpf":"52998224725","nome":"Fulano de Tal"},"valor":{"original":"123.45"},
          "chave":"{{pix_key}}","solicitacaoPagador":"Cobrança de teste","infoAdicionais":[{"nome":"Pedido","valor":"1234"}]}
cobv_body={"calendario":{"dataDeVencimento":"2030-12-31","validadeAposVencimento":30},"devedor":{"cnpj":"11222333000181","nome":"Empresa Teste Ltda"},
           "valor":{"original":"500.00"},"chave":"{{pix_key}}","solicitacaoPagador":"Fatura de teste"}
bcb=folder("API BCB — /api/pix/v2 (token_partner)",[
  folder("Cobrança imediata (cob)",[
    req("Criar cobrança com txid (PUT)","PUT","{{base_url}}/api/pix/v2/cob/{{txid}}",cob_body,pre=GEN_TXID,test=status_test(200,201)+ETAG_SAVE),
    req("Criar cobrança com txid gerado (POST)","POST","{{base_url}}/api/pix/v2/cob",cob_body,
        test=status_test(201)+["pm.collectionVariables.set('txid', pm.response.json().txid);"]+ETAG_SAVE),
    req("Consultar cobrança","GET","{{base_url}}/api/pix/v2/cob/{{txid}}",test=status_test(200)+ETAG_SAVE),
    req("Consultar cobrança (If-None-Match → 304)","GET","{{base_url}}/api/pix/v2/cob/{{txid}}",headers={"If-None-Match":"{{etag}}"},test=status_test(200,304)),
    req("Revisar cobrança (PATCH, exige If-Match)","PATCH","{{base_url}}/api/pix/v2/cob/{{txid}}",
        {"valor":{"original":"150.00"},"solicitacaoPagador":"Valor revisado"},headers={"If-Match":"{{etag}}"},test=status_test(200)+ETAG_SAVE),
    req("Remover cobrança (status REMOVIDA_PELO_USUARIO_RECEBEDOR)","PATCH","{{base_url}}/api/pix/v2/cob/{{txid}}",
        {"status":"REMOVIDA_PELO_USUARIO_RECEBEDOR"},headers={"If-Match":"{{etag}}"},test=status_test(200)),
    req("Listar cobranças por período","GET","{{base_url}}/api/pix/v2/cob?inicio={{inicio}}&fim={{fim}}&~cpf=52998224725&~cnpj=&~status=ATIVA&~locationPresente=false&paginacao.paginaAtual=0&paginacao.itensPorPagina=100",test=status_test(200)),
  ]),
  folder("Cobrança com vencimento (cobv)",[
    req("Criar cobv (PUT)","PUT","{{base_url}}/api/pix/v2/cobv/{{txid_cobv}}",cobv_body,
        pre=["const c='abcdefghijklmnopqrstuvwxyz0123456789'; let t=''; for(let i=0;i<32;i++) t+=c[Math.floor(Math.random()*c.length)]; pm.collectionVariables.set('txid_cobv', t);"],test=status_test(200,201)),
    req("Consultar cobv","GET","{{base_url}}/api/pix/v2/cobv/{{txid_cobv}}",test=status_test(200)),
  ]),
  folder("Pix recebidos",[
    req("Consultar Pix por e2eid","GET","{{base_url}}/api/pix/v2/pix/{{e2eid}}",test=status_test(200,404),desc="Requer um Pix recebido (consumer de pix.spi.payment-settled.v1)."),
    req("Listar Pix recebidos por período","GET","{{base_url}}/api/pix/v2/pix?inicio={{inicio}}&fim={{fim}}&~txid=&~cpf=&~cnpj=&paginacao.paginaAtual=0&paginacao.itensPorPagina=100",test=status_test(200)),
  ]),
  folder("Devolução",[
    req("Solicitar devolução (PUT)","PUT","{{base_url}}/api/pix/v2/pix/{{e2eid}}/devolucao/{{refund_id}}",
        {"valor":"10.00","natureza":"ORIGINAL","descricao":"Devolução de teste"},test=status_test(200,201,404,422),
        desc="Precisa de um Pix recebido (chega pelo tópico pix.spi.payment-settled.v1). Sem ele: 404.",
        pre=["if(!pm.collectionVariables.get('refund_id')) pm.collectionVariables.set('refund_id','dev'+Date.now());"]),
    req("Consultar devolução","GET","{{base_url}}/api/pix/v2/pix/{{e2eid}}/devolucao/{{refund_id}}",test=status_test(200,404)),
  ]),
],auth="token_partner")

# ---------------- Gestão /v1 ----------------
gestao=folder("Gestão — /v1 (token_backoffice)",[
  folder("Chaves Pix",[
    req("0. Liberar chaves (limpeza; limite de 5 chaves vivas por conta PF)","GET","{{base_url}}/v1/pix-keys?accountId={{account_id}}&size=100",
        test=status_test(200)+CLEANUP,
        desc="Apaga pela API as chaves vivas deixadas por execuções anteriores, exceto a chave do seed. Como o limite é por conta (PF 5 / PJ 20), a collection se limpa: cada chave criada abaixo é excluída ao final da pasta."),
    req("Registrar chave (EVP)","POST","{{base_url}}/v1/pix-keys",{"accountId":"{{account_id}}","pixKeyType":"EVP"},
        headers={"Idempotency-Key":"{{idempotency_key}}"},pre=IDEMP,
        test=status_test(201)+["const j=pm.response.json(); pm.collectionVariables.set('pix_key_id', j.id);"]+ETAG_SAVE),
    req("Consultar chave","GET","{{base_url}}/v1/pix-keys/{{pix_key_id}}",test=status_test(200)+ETAG_SAVE),
    req("Consultar chave (If-None-Match → 304)","GET","{{base_url}}/v1/pix-keys/{{pix_key_id}}",headers={"If-None-Match":"{{etag}}"},test=status_test(200,304)),
    req("Listar chaves da conta","GET","{{base_url}}/v1/pix-keys?accountId={{account_id}}&~status=ACTIVE&page=0&size=20",test=status_test(200)),
    req("Excluir chave EVP (exige If-Match)","POST","{{base_url}}/v1/pix-keys/{{pix_key_id}}/deletion",{"reason":"USER_REQUESTED"},headers={"If-Match":"{{etag}}"},test=status_test(200)),
    req("Registrar chave (e-mail)","POST","{{base_url}}/v1/pix-keys",{"accountId":"{{account_id}}","pixKeyType":"EMAIL","pixKey":"{{email_key}}"},
        headers={"Idempotency-Key":"{{idempotency_key}}"},pre=IDEMP+["pm.collectionVariables.set('email_key', 'teste.'+Date.now()+'@example.com');"],
        test=status_test(201)+["pm.collectionVariables.set('email_key_id', pm.response.json().id);"]+ETAG_SAVE),
    req("Prova de posse 1/3 — solicitar código (e-mail/telefone)","POST","{{base_url}}/v1/pix-keys/{{email_key_id}}/ownership-proof/challenges",
        test=status_test(202)+["const j=pm.response.json(); pm.test('o código NÃO vem na resposta', () => pm.expect(j.code).to.be.undefined);"],
        desc="Envia um código de 6 dígitos ao dono do e-mail/telefone. Espera mínima entre pedidos: 429 PIX-429-002 com Retry-After. Só funciona com chave em PENDING_OWNERSHIP_PROOF."),
    req("Prova de posse 2/3 — ler o código no serviço de notificação FALSO (só dev)","GET","{{mocks_url}}/otp/v1/last?destination={{email_key}}",auth="none",
        test=status_test(200)+["pm.collectionVariables.set('otp_code', pm.response.json().code);"],
        desc="Em dev o `mocks` faz o papel do serviço de notificação e guarda o último código por destino. Em hml/prd o código chega ao e-mail/telefone real."),
    req("Prova de posse 3/3 — verificar o código","POST","{{base_url}}/v1/pix-keys/{{email_key_id}}/ownership-proof/verifications",{"code":"{{otp_code}}"},
        test=status_test(200)+["pm.test('chave foi para PENDING_DICT', () => pm.expect(pm.response.json().status).to.eql('PENDING_DICT'));"]+ETAG_SAVE,
        desc="Código errado: 422 PIX-422-014 (com as tentativas restantes). Vencido: PIX-422-015. Tentativas esgotadas: PIX-422-016."),
    req("Consultar chave de terceiro no DICT (lookup)","POST","{{base_url}}/v1/pix-keys/lookups",
        {"payerAccountId":"{{account_id}}","pixKeyType":"EMAIL","pixKey":"outra@example.com"},test=status_test(200,404,502,503),desc="Depende do dict-gateway (externo, fora deste repositório): 502/503 até ele existir."),
    req("Excluir chave e-mail (limpeza final)","POST","{{base_url}}/v1/pix-keys/{{email_key_id}}/deletion",{"reason":"USER_REQUESTED"},headers={"If-Match":"{{etag}}"},test=status_test(200)),
  ]),
  folder("Ordens de pagamento",[
    req("Decodificar BR Code","POST","{{base_url}}/v1/payment-orders/decodings",{"payload":"{{br_code}}"}),
    req("Criar pagamento por chave","POST","{{base_url}}/v1/payment-orders",
        {"payerAccountId":"{{account_id}}","method":"KEY","amount":"25.90","destination":{"pixKeyType":"EMAIL","pixKey":"outra@example.com"},"payerMessage":"Pagamento de teste","deviceId":"device-postman"},
        headers={"Idempotency-Key":"{{idempotency_key}}"},pre=IDEMP,
        test=status_test(201,422,502,503)+["if([200,201].includes(pm.response.code)){const j=pm.response.json(); if(j.id) pm.collectionVariables.set('payment_order_id', j.id);}"],
        desc="Idempotency-Key é obrigatório. Reenviar a mesma chave devolve a resposta original com `Idempotent-Replayed: true`. Depende de core-ledger e dict-gateway (externos)."),
    req("Criar pagamento manual (dados bancários)","POST","{{base_url}}/v1/payment-orders",
        {"payerAccountId":"{{account_id}}","method":"MANUAL","amount":"100.00","destination":{"ispb":"60746948","branch":"0001","account":"123456","accountType":"CACC","name":"Beneficiário Teste","document":"52998224725"}},
        headers={"Idempotency-Key":"{{idempotency_key}}"},pre=IDEMP,test=status_test(201,422,502,503)+["if([200,201].includes(pm.response.code)){const j=pm.response.json(); if(j.id) pm.collectionVariables.set('payment_order_id', j.id);}"]),
    req("Criar pagamento por QR estático","POST","{{base_url}}/v1/payment-orders",
        {"payerAccountId":"{{account_id}}","method":"QR_STATIC","qrPayload":"{{br_code}}"},headers={"Idempotency-Key":"{{idempotency_key}}"},pre=IDEMP,test=status_test(201,422,502,503)+["if([200,201].includes(pm.response.code)){const j=pm.response.json(); if(j.id) pm.collectionVariables.set('payment_order_id', j.id);}"]),
    req("Consultar pagamento","GET","{{base_url}}/v1/payment-orders/{{payment_order_id}}",test=status_test(200,404)+ETAG_SAVE),
    req("Listar pagamentos da conta","GET","{{base_url}}/v1/payment-orders?payerAccountId={{account_id}}&~from={{inicio}}&~to={{fim}}&page=0&size=20",test=status_test(200)),
    req("Cancelar pagamento agendado (exige If-Match)","POST","{{base_url}}/v1/payment-orders/{{payment_order_id}}/cancellation",headers={"If-Match":"{{etag}}"},
        pre=["// o job de reserva muda a versão da ordem a cada ~2 s: busca o ETag mais recente imediatamente antes de cancelar",
             "pm.sendRequest({ url: pm.variables.get('base_url') + '/v1/payment-orders/' + pm.collectionVariables.get('payment_order_id'), method: 'GET',",
             "  header: { Authorization: 'Bearer ' + pm.collectionVariables.get('token_backoffice') } }, (e, r) => { if (!e && r.headers.get('ETag')) pm.collectionVariables.set('etag', r.headers.get('ETag')); });"],
        test=status_test(200,412,422),
        desc="200 cancelou; 422 já passou de RESERVED (SENT_TO_SPI/SETTLED não cancelam); 412 a ordem mudou de versão entre o GET e o cancelamento (corrida normal: repita)."),
  ]),
  folder("Limites",[
    req("Listar limites da conta","GET","{{base_url}}/v1/limits/accounts/{{account_id}}",test=status_test(200)+ETAG_SAVE),
    req("Alterar limite (PUT, If-Match)","PUT","{{base_url}}/v1/limits/accounts/{{account_id}}/{{limit_scope}}",{"amount":"5000.00"},
        headers={"If-Match":"{{etag}}"},desc="limit_scope: PER_TRANSACTION, DAILY, NIGHTLY, NEW_PAYEE, DEVICE_UNREGISTERED_PER_TX, DEVICE_UNREGISTERED_DAILY."),
  ]),
],auth="token_backoffice")

# ---------------- Docs / operação ----------------
ops=folder("Docs e operação da aplicação (sem token)",[
  req("Health","GET","{{base_url}}/actuator/health",auth="none"),
  req("Health — readiness","GET","{{base_url}}/actuator/health/readiness",auth="none"),
  req("Health — liveness","GET","{{base_url}}/actuator/health/liveness",auth="none"),
  req("Info","GET","{{base_url}}/actuator/info",auth="none"),
  req("Métricas Prometheus","GET","{{base_url}}/actuator/prometheus",auth="none"),
  req("OpenAPI (JSON)","GET","{{base_url}}/v3/api-docs",auth="none"),
  req("AsyncAPI / Springwolf (JSON)","GET","{{base_url}}/springwolf/docs",auth="none"),
  req("Swagger UI","GET","{{base_url}}/swagger-ui/index.html",auth="none"),
],auth=None)

infra=folder("Infra (Kafka, Schema Registry, Connect, observabilidade)",[
  folder("Kafka Connect / Debezium",[
    req("Listar conectores","GET","{{connect_url}}/connectors",auth="none"),
    req("Status do conector de outbox","GET","{{connect_url}}/connectors/pix-outbox-connector/status",auth="none"),
    req("Config do conector de outbox","GET","{{connect_url}}/connectors/pix-outbox-connector/config",auth="none"),
    req("Reiniciar conector (com tasks)","POST","{{connect_url}}/connectors/pix-outbox-connector/restart?includeTasks=true&onlyFailed=false",auth="none"),
    req("Plugins instalados","GET","{{connect_url}}/connector-plugins",auth="none"),
  ]),
  folder("Schema Registry",[
    req("Listar subjects","GET","{{schema_registry_url}}/subjects",auth="none"),
    req("Versões de um subject","GET","{{schema_registry_url}}/subjects/pix.payment.v1-value/versions",auth="none"),
    req("Último schema de um subject","GET","{{schema_registry_url}}/subjects/pix.payment.v1-value/versions/latest",auth="none"),
    req("Compatibilidade global","GET","{{schema_registry_url}}/config",auth="none"),
  ]),
  folder("Mocks de dev (dict-gateway + core-ledger)",[
    req("Health","GET","{{mocks_url}}/health",auth="none"),
    req("Estado em memória (chaves no DICT, reservas no ledger)","GET","{{mocks_url}}/__state",auth="none"),
  ]),
  folder("Observabilidade",[
    req("Prometheus — alvos","GET","{{prometheus_url}}/api/v1/targets",auth="none"),
    req("Prometheus — query (up)","GET","{{prometheus_url}}/api/v1/query?query=up",auth="none"),
    req("Prometheus — taxa HTTP do app","GET","{{prometheus_url}}/api/v1/query?query=sum(rate(http_server_requests_seconds_count[1m]))",auth="none"),
    req("Grafana — health","GET","{{grafana_url}}/api/health",auth="none"),
    req("Loki — ready (503 nos primeiros ~15s)","GET","{{loki_url}}/ready",auth="none"),
    req("Kafka UI — clusters","GET","{{kafka_ui_url}}/api/clusters",auth="none"),
  ]),
])

PRE_TOKEN=["// Renova sozinho o token do client usado pela requisição (partner → /api/pix/v2, backoffice → /v1).",
"const host = pm.variables.get('keycloak_url');",
"const realm = pm.variables.get('realm');",
"function ensure(tokVar, clientVar) {",
"  const exp = Number(pm.collectionVariables.get(tokVar + '_exp') || 0);",
"  if (pm.collectionVariables.get(tokVar) && Date.now() < exp) return;",
"  pm.sendRequest({",
"    url: `${host}/realms/${realm}/protocol/openid-connect/token`, method: 'POST',",
"    header: { 'Content-Type': 'application/x-www-form-urlencoded' },",
"    body: { mode: 'urlencoded', urlencoded: [",
"      { key: 'grant_type', value: 'client_credentials' },",
"      { key: 'client_id', value: pm.variables.get(clientVar) },",
"      { key: 'client_secret', value: pm.variables.get('client_secret') } ] }",
"  }, (err, res) => {",
"    if (err || res.code !== 200) { console.error('Falha ao obter token', tokVar, err || res.text()); return; }",
"    const j = res.json();",
"    pm.collectionVariables.set(tokVar, j.access_token);",
"    pm.collectionVariables.set(tokVar + '_exp', Date.now() + (j.expires_in - 15) * 1000);",
"  });",
"}",
"const path = pm.request.url.getPath();",
"if (path.startsWith('/api/pix/v2')) ensure('token_partner', 'client_id_partner');",
"if (path.startsWith('/v1/')) ensure('token_backoffice', 'client_id_backoffice');",
"if (!pm.collectionVariables.get('e2eid')) { const c='ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'; let t='E'+pm.variables.get('ispb')+new Date().toISOString().replace(/[-:T]/g,'').slice(0,12); while(t.length<32) t+=c[Math.floor(Math.random()*c.length)]; pm.collectionVariables.set('e2eid', t); }",
"// janela do período usado nas listagens (últimas 24h)",
"pm.collectionVariables.set('inicio', new Date(Date.now() - 86400000).toISOString());",
"pm.collectionVariables.set('fim', new Date().toISOString());"]

variables={
 "base_url":"http://localhost:8088","keycloak_url":"http://localhost:18180","realm":"pix",
 "client_id_partner":"pix-partner-dev","client_id_backoffice":"pix-backoffice-dev","client_secret":"dev-secret-local-only",
 "keycloak_admin_user":"admin","keycloak_admin_password":"admin_local_only",
 "connect_url":"http://localhost:18083","schema_registry_url":"http://localhost:18081","prometheus_url":"http://localhost:19090",
 "grafana_url":"http://localhost:13000","loki_url":"http://localhost:13100","mocks_url":"http://localhost:18090","kafka_ui_url":"http://localhost:18080",
 "ispb":"12345678","account_id":"00000000-0000-0000-0000-000000000001","pix_key":"123e4567-e89b-42d3-a456-426655440000","br_code":"00020126580014br.gov.bcb.pix0136123e4567-e89b-42d3-a456-426655440000520400005303986540510.005802BR5913Fulano de Tal6009SAO PAULO62070503***63048654",
 "limit_scope":"DAILY","token_partner":"","token_partner_exp":"","token_backoffice":"","token_backoffice_exp":"","keycloak_admin_token":"",
 "txid":"","txid_cobv":"","e2eid":"","refund_id":"","pix_key_id":"","payment_order_id":"","email_key":"","email_key_id":"","otp_code":"","etag":"","idempotency_key":"","inicio":"","fim":""}
STATIC=["base_url","keycloak_url","realm","client_id_partner","client_id_backoffice","client_secret","keycloak_admin_user","keycloak_admin_password","connect_url","schema_registry_url","prometheus_url","grafana_url","loki_url","kafka_ui_url","ispb","account_id","pix_key","br_code","limit_scope"]
coll={"info":{"name":"ms-pix-manager","_postman_id":str(uuid.uuid5(uuid.NAMESPACE_DNS,"ms-pix-manager")),
  "description":"Todas as chamadas do ms-pix-manager (API BCB /api/pix/v2 + gestão /v1), Keycloak e infraestrutura do docker compose.\n\nUso: importe esta collection e o ambiente `ms-pix-manager.local.postman_environment.json`, selecione o ambiente e rode. Os tokens são obtidos/renovados automaticamente.",
  "schema":"https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
 "event":[ev("prerequest",PRE_TOKEN)],
 "variable":[{"key":k,"value":v} for k,v in variables.items() if k not in STATIC],
 "item":[keycloak,bcb,gestao,ops,infra]}
json.dump(coll,open(OUT+'ms-pix-manager.postman_collection.json','w'),indent=2,ensure_ascii=False)
env={"id":str(uuid.uuid5(uuid.NAMESPACE_DNS,"ms-pix-manager-local")),"name":"ms-pix-manager — local (docker compose)",
 "values":[{"key":k,"value":v,"type":"secret" if "secret" in k or "password" in k else "default","enabled":True} for k,v in variables.items() if k in STATIC],
 "_postman_variable_scope":"environment"}
json.dump(env,open(OUT+'ms-pix-manager.local.postman_environment.json','w'),indent=2,ensure_ascii=False)
print("ok")
