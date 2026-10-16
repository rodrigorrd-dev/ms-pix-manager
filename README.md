# ms-pix-manager

Microsserviço de gestão Pix (recebedor, pagador, DICT, MED) — Java 21 · Spring Boot 3 · Hexagonal · PostgreSQL + Liquibase · Kafka (Avro + Schema Registry) · Debezium Outbox.

## Subir (1 comando)

Pré-requisito: Docker com Compose v2. Nada mais (sem Java, Maven ou Python no host para subir).

```bash
docker compose up -d --build
```

A primeira vez baixa ~15 imagens e as dependências Maven (vários minutos, depende da rede); as seguintes levam cerca de 1 minuto. Funciona sem `.env` (defaults embutidos); para personalizar portas, chaves e URLs: `cp .env.example .env`.

```bash
docker compose ps                       # tudo healthy/running; kafka-init, seed e connector-init terminam (Exited 0)
curl localhost:8088/actuator/health     # {"status":"UP"}
python3 scripts/validate-api.py         # opcional: valida todos os endpoints (143 verificações)
python3 scripts/demo-flow.py            # opcional: chaves (com OTP) → QR Code → webhook → pagar → receber → devolver, agendar, MED, Pix Automático (49 passos)
python3 scripts/chaos-test.py           # opcional: derruba core/Kafka/Debezium/Postgres e confere a recuperação sozinha (SÓ dev)
docker compose down                     # para (dados do Postgres ficam no volume); `down -v` apaga tudo
```

Ordem de subida (o compose cuida): postgres → kafka → kafka-init (tópicos) → schema-registry → **mocks** (dict-gateway e core-ledger falsos) → **app** (Liquibase cria o schema) → **seed** (dados de dev) → **connector-init** (registra o Debezium, que precisa da tabela `outbox_event`).

> **Produção:** veja [`subida-producao.md`](subida-producao.md) (variáveis, banco, Kafka, Debezium, IdP, rede e pendências). Tudo deste README abaixo é ambiente de **desenvolvimento**.

## Como usar cada recurso

| Recurso | URL / comando | Como usar |
|---|---|---|
| **API REST** | http://localhost:8088 | Exige JWT. Pegue o token no Keycloak (abaixo) e envie `Authorization: Bearer …`. Rotas BCB em `/api/pix/v2` e gestão em `/v1`. |
| **Swagger UI** | [/swagger-ui/index.html](http://localhost:8088/swagger-ui/index.html) · JSON em `/v3/api-docs` | Contratos (campos, validações, respostas) e **Try it out**: clique em **Authorize**, cole o `access_token` do Keycloak (sem o prefixo `Bearer`) e execute. Token do client errado dá 403. |
| **Postman** | `docs/postman/` | Importe a collection e o ambiente `ms-pix-manager — local`. Tokens são obtidos e renovados sozinhos; veja a seção abaixo. |
| **Keycloak** | http://localhost:18180 (`admin` / `admin_local_only`) | Console do IdP, realm `pix`. Clients de dev (secret `dev-secret-local-only`): `pix-partner-dev` (escopos BCB: `cob.*`, `cobv.*`, `pix.*`, só a conta de seed) e `pix-backoffice-dev` (gestão `/v1`, todas as contas). O acesso a contas vem do claim `pix_accounts`. Realm em `docker/keycloak/realm-pix.json` (importado no start; para reimportar: `docker compose up -d --force-recreate keycloak`). |
| **Eventos (AsyncAPI)** | [/springwolf/asyncapi-ui.html](http://localhost:8088/springwolf/asyncapi-ui.html) · JSON em `/springwolf/docs` | Catálogo dos tópicos Kafka que o serviço consome e publica, com schema de cada mensagem. |
| **Kafka UI** | http://localhost:18080 | *Topics*: ver mensagens e partições dos `pix.*.v1` (e `-retry-N`/`.dlt` dos consumers). *Schema Registry*: schemas Avro. *Kafka Connect*: estado do conector `pix-outbox-connector`. *Consumers*: lag por grupo. |
| **Schema Registry** | http://localhost:18081 | `curl localhost:18081/subjects` lista os schemas registrados (o app registra no start, `KAFKA_AUTO_REGISTER_SCHEMAS=true` só em dev). |
| **Kafka Connect / Debezium** | http://localhost:18083 | `curl localhost:18083/connectors/pix-outbox-connector/status` (esperado `RUNNING`). Reinício: `curl -X POST "localhost:18083/connectors/pix-outbox-connector/restart?includeTasks=true"`. Re-registrar: `docker compose run --rm connector-init`. |
| **PostgreSQL** | `docker exec -it pix-postgres psql -U pix -d pixmanager` · ou `localhost:55432` (`pix` / `pix_local_only`) | Inspecionar dados; `outbox_event` mostra eventos ainda não publicados. A PII (documento, chave) fica **cifrada** no banco. |
| **Mocks (dict-gateway + core-ledger)** | http://localhost:18090 (`/health`, `/__state`) | Os dois sistemas externos que o app chama, **falsos e em memória** (`docker/mocks/mock_servers.py`): o DICT registra/consulta chaves e o ledger reserva/confirma/cancela saldo. Sem eles, chaves não ativam e pagamento não sai do `VALIDATED`. O mesmo serviço faz o papel do **serviço de notificação do OTP** (`POST /otp/v1/messages`; `GET /otp/v1/last?destination=<e-mail>` devolve o último código, só para testes). Cenários por valor: reserva de **99,99** → saldo insuficiente, **66,66** → conta bloqueada, **50,50** → falha técnica (503); chave com `naoexiste` → 404 no DICT. Para usar os sistemas reais: `DICT_GATEWAY_URL_COMPOSE` / `CORE_LEDGER_URL_COMPOSE` no `.env`. |
| **Receptor de webhook (fictício)** | https://localhost:18443/webhook-receiver/last | Em HTTPS com certificado de dev autoassinado (`docker/mocks/dev-cert.pem`, pública: **só dev**), dentro da rede do compose é `https://mocks:8443/webhook-receiver/{ok\|fail}`. Valida a assinatura e guarda o que chegou (`signatureValid`, `timestampFresh`). `…/fail` responde 503 para ver retry/DEAD. |
| **Simulador do SPI** | `python3 scripts/simulate-spi.py …` | Faz o papel do spi-gateway: publica no Kafka (Avro) o status do pagamento enviado, um **Pix recebido** e o resultado de uma **devolução**. Detalhes em "Testar o fluxo completo". |
| **Gerador de BR Code** | `python3 scripts/brcode.py static\|dynamic …` | Gera o "Pix copia e cola" (texto do QR Code), estático (de uma chave) ou dinâmico (do `location` de uma cobrança). A API só **lê** BR Code; quem gera é o recebedor. |
| **Seed de dev** | `docker/seed/dev-seed.sql` | Titular (CPF fictício), conta `00000000-0000-0000-0000-000000000001` (ISPB 12345678) e chave EVP ativa `123e4567-e89b-42d3-a456-426655440000`. Idempotente: `docker compose run --rm --no-deps seed`. Cifrado com as chaves de dev do `.env.example`; se trocá-las, regenere com `node docker/seed/generate-dev-seed.js > docker/seed/dev-seed.sql`. |
| **Métricas** | http://localhost:19090 (Prometheus) | *Status → Targets* (app em `app:8080`), *Alerts* (regras em `ops/prometheus/alert-rules.yml`: lag, DLT, slot do Debezium, latência p95, taxa de erro). Métricas cruas: `/actuator/prometheus`. |
| **Traces** | http://localhost:13000 (Grafana, sem login) | O app envia traces (OTLP) ao collector → Tempo (já recebe traces de `ms-pix-manager`; Tempo e Loki levam ~15 s para ficar `ready` após subir). No Grafana, adicione o datasource **Tempo** (`http://tempo:3200`) e use *Explore*. O `traceId` aparece em todo erro da API (`problem+json`). |
| **Dashboards** | http://localhost:13000 → pasta **Pix** → *ms-pix-manager — operação* | Provisionado: datasources **Prometheus, Tempo e Loki** e um dashboard com ordens/chaves/reservas presas, DLT, circuit breakers, slot do Debezium, latência p95, erros, pool do banco, lag dos consumers, outbox e erros do app. Fonte: `ops/grafana/`. |
| **Logs** | `docker compose logs -f app` · Grafana → *Explore* → Loki: `{container="pix-app", level="ERROR"}` | O app escreve no console (JSON fora do perfil `local`, com PII mascarada). No dev o **Promtail** (`ops/promtail`) envia os logs dos contêineres `pix-*` ao Loki (rótulos `container`, `service`, `level`); **em produção** use o agente de logs da plataforma (o Promtail lê o socket do Docker: só dev). |
| **Health** | `/actuator/health`, `/actuator/health/readiness`, `/actuator/health/liveness` | Sem token. Só `health`, `info` e `prometheus` estão expostos. |

### Autenticação (token)

```bash
# gestão /v1 (chaves, pagamentos, limites)
TOKEN=$(curl -s -d grant_type=client_credentials -d client_id=pix-backoffice-dev -d client_secret=dev-secret-local-only \
  http://localhost:18180/realms/pix/protocol/openid-connect/token | jq -r .access_token)
curl -H "Authorization: Bearer $TOKEN" "localhost:8088/v1/pix-keys?accountId=00000000-0000-0000-0000-000000000001"
```
Para a API BCB (`/api/pix/v2/...`) use `client_id=pix-partner-dev`. Token do client errado dá **403**; sem token, **401**.

### Exemplos rápidos

```bash
A=00000000-0000-0000-0000-000000000001
PARTNER=$(curl -s -d grant_type=client_credentials -d client_id=pix-partner-dev -d client_secret=dev-secret-local-only \
  http://localhost:18180/realms/pix/protocol/openid-connect/token | jq -r .access_token)   # TOKEN = o do backoffice, acima
# cobrança imediata (PUT cria; txid de 26–35 caracteres alfanuméricos)
curl -X PUT -H "Authorization: Bearer $PARTNER" -H 'Content-Type: application/json' localhost:8088/api/pix/v2/cob/abcd1234efgh5678ijkl9012mnop3456 \
  -d '{"calendario":{"expiracao":3600},"devedor":{"cpf":"52998224725","nome":"Fulano"},"valor":{"original":"10.00"},"chave":"123e4567-e89b-42d3-a456-426655440000"}'

# alterar limite: If-Match é obrigatório (ETag vem do GET); "*" aceita qualquer versão
curl -X PUT -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -H 'If-Match: *' \
  localhost:8088/v1/limits/accounts/$A/DAILY -d '{"amount":"5000.00"}'

# pagamento: Idempotency-Key é obrigatório; repetir a mesma chave devolve a resposta original
curl -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" localhost:8088/v1/payment-orders \
  -d '{"payerAccountId":"'$A'","method":"MANUAL","amount":"100.00","destination":{"ispb":"60746948","branch":"0001","account":"123456","accountType":"CACC","name":"Beneficiário","document":"52998224725"}}'
```
Convenções da API: erros em `application/problem+json` (RFC 9457) com `code` estável (`PIX-4xx-nnn`) e `traceId`; escrita concorrente usa `ETag`/`If-Match` (**428** sem o header, **412** versão antiga); `GET` de cobrança (`cob`/`cobv`), chave Pix e ordem de pagamento aceita `If-None-Match` (**304**; aceita `*`, lista e `W/`); listas em `/v1` paginam com `page`/`size` (máx. 100).

### Testar o fluxo completo (chaves, QR Code e Pix)

Tudo roda local: o `docker compose up` já traz os mocks do DICT/core, e o SPI é simulado por script. **Atalho que executa o roteiro inteiro e confere cada passo:** `python3 scripts/demo-flow.py`. Abaixo, o que ele faz e como repetir à mão.

**Como funcionam as chaves** (o que confunde no começo)
- Chave é por **conta** (não por pessoa): máximo **5 vivas para PF e 20 para PJ**. `DELETED`/`REJECTED` não contam. A conta de seed é PF e já usa 1 (a chave do seed), então sobram 4. Se bater `PIX-422-011`, exclua chaves (`POST /v1/pix-keys/{id}/deletion`, com `If-Match`). A collection e os scripts se limpam sozinhos.
- Tipos: `CPF`, `CNPJ`, `PHONE`, `EMAIL` (precisam do valor em `pixKey`) e **`EVP` (chave aleatória, o sistema gera o UUID)**.
- Ciclo: `POST /v1/pix-keys` → **`PENDING_DICT`** → o job de ativação registra no DICT a cada ~3 s → **`ACTIVE`** (com os mocks leva poucos segundos). `EMAIL`/`PHONE` nascem em **`PENDING_OWNERSHIP_PROOF`** e exigem **prova de posse por código OTP** antes de seguir ao DICT: `POST /v1/pix-keys/{id}/ownership-proof/challenges` (envia o código, 202) e `POST …/verifications` com `{"code":"123456"}` (acertando, vai a `PENDING_DICT` e então `ACTIVE`). Código de 6 dígitos, vale 5 min, 5 tentativas, 60 s entre pedidos, 10 por dia (429 com `Retry-After`).
- `pixKey` não volta no JSON (PII); só no momento do cadastro você sabe o valor. A chave de seed vale `123e4567-e89b-42d3-a456-426655440000`.

**1. Criar uma chave e vê-la ativa**
```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  localhost:8088/v1/pix-keys -d '{"accountId":"00000000-0000-0000-0000-000000000001","pixKeyType":"EVP"}' | jq '{id,status}'
sleep 5; curl -s -H "Authorization: Bearer $TOKEN" localhost:8088/v1/pix-keys/<id> | jq .status      # "ACTIVE"
```

**2. Gerar o QR Code (copia e cola)**
```bash
python3 scripts/brcode.py static  --key outra@example.com --name "Maria" --amount 12.34      # estático (uma chave)
python3 scripts/brcode.py dynamic --url <location da cobrança> --amount 50.00                # dinâmico (cobrança)
python3 scripts/brcode.py static --key <chave> --qr                                          # + desenha o QR no terminal (npx qrcode)
# a API decodifica e valida o CRC:
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' localhost:8088/v1/payment-orders/decodings -d '{"payload":"<copia e cola>"}'
```
Cobrança dinâmica: `PUT /api/pix/v2/cob/{txid}` devolve `location`; o BR Code dinâmico é construído com ele (o comando acima). **Pagar** QR dinâmico (`method: QR_DYNAMIC`): o ms busca o payload JWS na URL do QR, valida assinatura e cadeia de certificados e paga o valor/chave **do payload**. No dev, o serviço `mocks` serve `https://mocks:8443/qr/v2/{ok|v-<valor>|paga|expirada|adulterado}` (assinado com o certificado de dev):
```bash
python3 scripts/brcode.py dynamic --url mocks:8443/qr/v2/v-12.34   # copia e cola; use em POST /v1/payment-orders {method:QR_DYNAMIC,qrPayload}
```
Em produção é obrigatório configurar `PIX_QR_TRUST_STORE_PATH` (ICP-Brasil); sem ele o QR dinâmico é recusado. O estático (`QR_STATIC`) e por chave (`KEY`) pagam como antes.

**3. Fazer um Pix** (pagador = conta de seed)
```bash
# por chave (o mock do DICT resolve o favorecido "Maria Beneficiária"); guarde o id e o endToEndId
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" localhost:8088/v1/payment-orders \
  -d '{"payerAccountId":"00000000-0000-0000-0000-000000000001","method":"KEY","amount":"25.90","destination":{"pixKeyType":"EMAIL","pixKey":"outra@example.com"}}' | jq '{id,status,endToEndId}'
# VALIDATED → RESERVED (ledger reservou, em ~2 s). Agora o "SPI":
python3 scripts/simulate-spi.py payment-status <endToEndId> SENT       # → SENT_TO_SPI
python3 scripts/simulate-spi.py payment-status <endToEndId> SETTLED    # → SETTLED (ledger confirma a reserva)
python3 scripts/simulate-spi.py payment-status <endToEndId> REJECTED --reason AC03   # alternativa: rejeitado
```
Estados: `CREATED → VALIDATED → RESERVED → SENT_TO_SPI → SETTLED` (ou `REJECTED`/`FAILED_TECHNICAL`/`CANCELLED`). Só dá para **cancelar** (`POST …/cancellation`) até `RESERVED`. **Limites padrão PF** que podem recusar o pagamento (422 `PIX-422-050`, com o `paymentOrderId` no corpo): R$ 100 para **novo favorecido**, R$ 1.000 no **período noturno**, R$ 5.000/dia, R$ 200 por transação e R$ 1.000/dia em **dispositivo não cadastrado** (o `deviceId` do pedido); veja/altere em `/v1/limits/accounts/{id}`.

**4. Receber um Pix e devolver**
```bash
# 1) crie a cobrança (token do pix-partner-dev) e guarde o txid
# 2) o SPI avisa que o Pix chegou na conta de seed:
python3 scripts/simulate-spi.py pix-received --amount 50.00 --txid <txid>      # imprime o endToEndId
curl -s -H "Authorization: Bearer $PARTNER" localhost:8088/api/pix/v2/pix/<endToEndId> | jq     # Pix recebido; a cobrança vira CONCLUIDA
# 3) devolver (PUT cria; o id é livre, 1–35 alfanuméricos):
curl -s -X PUT -H "Authorization: Bearer $PARTNER" -H 'Content-Type: application/json' localhost:8088/api/pix/v2/pix/<endToEndId>/devolucao/dev001 \
  -d '{"valor":"10.00","natureza":"ORIGINAL","descricao":"teste"}' | jq .status          # EM_PROCESSAMENTO
python3 scripts/simulate-spi.py refund-status <rtrId> SETTLED --e2e <endToEndId>       # rtrId vem em .devolucoes[0].rtrId → DEVOLVIDO
```

**5. Ser avisado de cada Pix recebido (webhook)**
```bash
# a chave do recebedor aponta para o seu endpoint (HTTPS). No dev use o receptor fictício do serviço `mocks` (certificado de dev):
curl -s -X PUT -H "Authorization: Bearer $PARTNER" -H 'Content-Type: application/json' \
  localhost:8088/api/pix/v2/webhook/123e4567-e89b-42d3-a456-426655440000 -d '{"webhookUrl":"https://mocks:8443/webhook-receiver/ok"}' | jq    # 201 novo / 200 atualiza
# receba um Pix numa cobrança dessa chave (etapa 4) e, em ~2 s, veja o que o receptor recebeu:
curl -s localhost:18090/webhook-receiver/last | jq '{signatureValid, timestampFresh, body}'
curl -s -X DELETE -H "Authorization: Bearer $PARTNER" localhost:8088/api/pix/v2/webhook/123e4567-e89b-42d3-a456-426655440000    # 204
```
O app faz `POST <webhookUrl>/pix` com o corpo da API Pix e os cabeçalhos `x-pix-timestamp` e `x-pix-signature: v1=HMAC-SHA256(chave, timestamp + "." + corpo)`; só **2xx** é sucesso (senão retry com backoff até 8 tentativas ⇒ `DEAD`, reenvio em `POST /v1/webhook-deliveries/{id}/resend` com escopo `webhook.admin`). A URL precisa ser **HTTPS** e de host público (anti-SSRF; no dev o perfil `local` relaxa só o host e o certificado). Contrato completo em `docs/05` (“Entrega do webhook”) e runbook em `docs/runbooks/webhook-entregas.md`.

**O que NÃO é real aqui:** o DICT, o core bancário, o serviço de notificação (OTP) e o SPI são simulados (mocks e script); o gateway do MED/Pix Automático (contratos rascunho) e a entrada de solicitações/notificações do SPI por Kafka também são simulados; **o que ainda falta** está em `docs/pendencias-funcionais.md`. Para testar contra os sistemas reais, aponte as URLs no `.env`.

### Postman

Importe **`docs/postman/ms-pix-manager.postman_collection.json`** e **`docs/postman/ms-pix-manager.local.postman_environment.json`** e selecione o ambiente `ms-pix-manager — local`. São 79 requisições, em pastas: **Keycloak** (tokens dos dois clients, OIDC, JWKS, admin), **API BCB** (`cob`, `cobv`, Pix recebidos, devolução), **Gestão `/v1`** (chaves, pagamentos, limites), **Docs e operação** (actuator, OpenAPI, AsyncAPI) e **Infra** (Connect, Schema Registry, mocks, Prometheus, Grafana, Loki, Kafka UI).
- Não precisa pegar token: um pre-request obtém e renova `token_partner` (`/api/pix/v2`) e `token_backoffice` (`/v1`).
- Scripts guardam `txid`, `ETag`, ids e `Idempotency-Key`: rode em ordem ou use **Run collection**. Pode repetir à vontade: a pasta "Chaves Pix" apaga as pendentes antigas e exclui as que cria (limite de 5 chaves vivas por conta PF).
- Mudou `APP_PORT` no `.env`? Ajuste `base_url` no ambiente.
- A collection é **gerada**: edite `docs/postman/generate-collection.py` e rode `python3 docs/postman/generate-collection.py`.
- Via linha de comando: `npx newman run docs/postman/ms-pix-manager.postman_collection.json -e docs/postman/ms-pix-manager.local.postman_environment.json`.

### Validar a API (`scripts/validate-api.py`)

```bash
python3 scripts/validate-api.py          # Python 3, só stdlib; exit code 1 se algo falhar
PIX_BASE_URL=http://localhost:8088 KEYCLOAK_URL=http://localhost:18180 python3 scripts/validate-api.py
```
Chama cada endpoint com casos de sucesso e de erro (sem token, escopo errado, `If-Match` ausente/antigo, replay de idempotência, payload inválido, limites) e confere status, `code` e cabeçalhos: 109 verificações. Também libera chaves pendentes antes e depois.

### Limites conhecidos

- **dict-gateway e core-ledger são sistemas externos** (fora deste repositório). No compose os dois são **mocks** (`mocks`); em hml/prd aponte para os reais. O comportamento dos mocks é o contrato que o app espera, mas não prova integração real.
- **Pix recebido** só existe se o spi-gateway publicar em `pix.spi.payment-settled.v1`; localmente use `scripts/simulate-spi.py pix-received`.
- **OTP:** em dev o código vai para o `mocks`; sem `PIX_OTP_SENDER` configurado (`none`, o padrão) a prova de posse responde 503. Os parâmetros do OTP (validade, tentativas, espera) são **de projeto**, não do regulamento.
- Kafka não tem volume: `down` apaga os tópicos (o `kafka-init` recria no `up`). O Postgres persiste em `pg-data` (`down -v` zera).
- Chaves, senhas e secrets do ambiente são **só de dev**; em hml/prd injete pelo cofre/orquestrador (o app não sobe sem `PIX_ISPB`, `PIX_CRYPTO_*`, `OAUTH2_*`, `DB_PASSWORD`).

## Desenvolvimento

**App na IDE** (infra no Docker):
```bash
docker compose up -d --no-deps postgres kafka kafka-init schema-registry kafka-connect keycloak mocks kafka-ui kafka-exporter otel-collector tempo loki prometheus grafana
docker compose run --rm --no-deps seed          # após o 1º start do app (Liquibase cria as tabelas)
./mvn.sh spring-boot:run                        # ou rode PixManagerApplication na IDE; lê ./.env (portas do host)
./docker/debezium/register-connector.sh         # registra o conector (usa o .env)
```
No `.env` o app usa as portas do host (Postgres `55432`, Kafka `19094`, Keycloak `18180`); dentro do compose o serviço `app` as troca pelos nomes internos. Para o Prometheus enxergar o app na IDE, troque o alvo em `ops/prometheus/prometheus.yml` por `host.docker.internal:<SERVER_PORT>`.

**Rebuild após mudar código:** `docker compose up -d --build app`.

**Testes** (o host não precisa de `mvn`): `./mvn.sh verify` (Maven 3.9 + JDK 21 em container; Testcontainers via socket do Docker). Estado atual: **452 unitários + 71 ITs, 0 falhas; cobertura unitária do `core` 95%** (gate: 70%). O `verify` completo sobe Postgres, Kafka e Debezium em contêineres e pede ~4 GB livres de RAM; com o stack do compose rodando junto, pare a observabilidade antes (`docker compose stop grafana prometheus tempo loki otel-collector kafka-ui kafka-exporter`) · um IT isolado: `./run-it.sh ChargeApiIT [linhas]` (saída vazia = passou). Gate JaCoCo ≥ 70% em `core`.

**Estrutura:** `api/` (REST e consumers, entrada) · `core/` (domínio e casos de uso, sem framework) · `infrastructure/` (adaptadores: banco, Kafka/outbox, HTTP) · `shared/` (config, erros, utilitários). Schema do banco em `src/main/resources/db/changelog` (Liquibase); contratos de evento em `src/main/resources/avro`.

## Documentação operacional e de qualidade

| Documento | Para quê |
|---|---|
| [`subida-producao.md`](subida-producao.md) | Tudo para subir em produção: variáveis, banco, Kafka, Debezium, IdP, rede, pendências |
| [`docs/runbooks/`](docs/runbooks/README.md) | Um runbook por alerta (app fora, circuit breaker, pagamento preso, DLT, slot do Debezium, webhook), rotação de chave, backup/DR |
| [`docs/load-test-results.md`](docs/load-test-results.md) | Testes de carga (Gatling): resultados, gargalo achado (pool do banco), o que NÃO provam |
| [`docs/security-scan.md`](docs/security-scan.md) | Trivy + Semgrep: 28 de 30 CVE HIGH/CRITICAL corrigidos, risco residual aceito, como repetir |
| [`docs/regulatory-verification.md`](docs/regulatory-verification.md) | Cada valor de `pix.policy.*` cruzado com fontes do BCB (✅/🟡/❌/🏢) |
| [`docs/pendencias-funcionais.md`](docs/pendencias-funcionais.md) | O que está e o que NÃO está implementado (QR dinâmico, agendamento, Pix Automático, MED) e a ordem sugerida |

## O que o projeto tem

| Módulo | Estado |
|---|---|
| account (leitura), ledger (reserva 2 fases + sweeper), pixkey, charge (cob/cobv), receivedpix, refund, limit, payment (chave/manual/QR estático, DICT, limites, SPI) | ✅ implementado, com testes unit + IT (Testcontainers) |
| **webhook** de Pix recebido (`/api/pix/v2/webhook`, entrega assinada, retry/DEAD, reenvio, anti-SSRF) e **prova de posse por OTP** de chave e-mail/telefone | ✅ implementado, com testes unit + IT + roteiro |
| **QR dinâmico** (pagar: busca + valida JWS/cadeia) · **Pix Agendado** (único/recorrente, worker, retentativa 18–21h com aviso) · **MED** (REPORTER/REPORTED, bloqueio, devolução MED, contestação) · **Pix Automático** (pagador: autorização, cobrança, execução, nova tentativa) | ✅ implementado, com unit + IT + roteiro no ambiente real. Limites: sem entrada por Kafka do SPI/DICT, sem rastreamento MED em camadas, sem revogação de certificado; prazos do Pix Automático não confirmados no regulamento (`docs/pendencias-funcionais.md`) |
| reconciliation | ⬜ não iniciado (só DDL/contratos) |

APIs: **BCB compatível** em `/api/pix/v2` (`cob`, `cobv`, `pix`, `pix/{e2eid}/devolucao`) e **gestão própria** em `/v1` (`pix-keys`, `payment-orders`, `limits/accounts/{id}`). Erros em RFC 9457 (`application/problem+json`), `If-Match`/`ETag` (428/412), `Idempotency-Key`.
Eventos: outbox transacional → Debezium → `pix.<aggregate>.v1` (Avro); consumers com retry não bloqueante + DLT e inbox idempotente.
Segurança: OAuth2 Resource Server (JWT/escopos), PII criptografada (AES-GCM + blind index HMAC), logs com máscara de PII.
Observabilidade: Actuator + Prometheus, traces OTLP → Tempo, Grafana, alertas em `ops/prometheus/alert-rules.yml`.

## Mapa da especificação

| # | Documento | Conteúdo |
|---|---|---|
| 00 | [`docs/00-index.md`](docs/00-index.md) | Índice, glossário rápido, como usar a spec |
| 01 | [`docs/01-rfc-ms-pix-manager.md`](docs/01-rfc-ms-pix-manager.md) | RFC (objetivo, baseline, alvo, gaps, ordem, convenções, DoD, backlog) |
| 02 | [`docs/02-requirements-business-rules.md`](docs/02-requirements-business-rules.md) | FR/BR com IDs, Gherkin, estados, matriz de rastreabilidade |
| 03 | [`docs/03-architecture.md`](docs/03-architecture.md) | Hexagonal, C4 (Mermaid), pacotes, ArchUnit, ADRs, stack |
| 04 | [`docs/04-data-model.md`](docs/04-data-model.md) | ERD, 37 tabelas, decisões, índices, LGPD, retenção |
| 05 | [`docs/05-api-contracts.md`](docs/05-api-contracts.md) | REST (compat BCB + gestão), erros RFC 9457, idempotência |
| 06 | [`docs/06-events-messaging.md`](docs/06-events-messaging.md) | Tópicos, Avro, outbox/Debezium, consumers, retry/DLT, inbox, lag |
| 07 | [`docs/07-non-functional.md`](docs/07-non-functional.md) | SLOs, resiliência, segurança, observabilidade, performance |
| 08 | [`docs/08-test-strategy.md`](docs/08-test-strategy.md) | Pirâmide, Testcontainers, contratos, concorrência, carga |
| 09 | [`docs/09-bcb-pix-research.md`](docs/09-bcb-pix-research.md) | Versões e regras extraídas da documentação oficial do BCB |
| 11 | [`docs/11-naming-glossary.md`](docs/11-naming-glossary.md) | **Nomenclatura em inglês**, termos regulatórios literais, sufixos, decisão de fio |
| 10 | [`docs/10-decisions-and-open-questions.md`](docs/10-decisions-and-open-questions.md) | **Log de decisões (rodada 1)** e pendências E1–E5, F1–F3 |

## Artefatos executáveis/contratuais

| Caminho | O que é | Validado |
|---|---|---|
| `db/changelog/**` | Liquibase (SQL puro + rollback), 11 arquivos, **37 tabelas + 1 MV** | ✅ aplicado em `postgres:16-alpine` (`wal_level=logical`) sem erros |
| `docs/api/bcb/api-pix-openapi-2.10.0.yaml` | Baseline oficial do BCB (cópia) | ✅ sha256 no `05` |
| `docs/api/ms-pix-manager.openapi.yaml` | API de gestão `/v1` | ✅ `openapi-spec-validator` (OpenAPI 3.0.3, 16 paths) |
| `src/main/resources/avro/*.avsc` | Envelope + 7 eventos (Avro, decimal/timestamp-micros) | ✅ parse (fastavro) e round-trip do `PixReceivedEvent` |
| `docker-compose.yml` (raiz) + `Dockerfile` | Postgres(logical), Kafka KRaft, Schema Registry, Connect/Debezium, Kafka UI, exporter, Keycloak (dev), OTel, Tempo, Loki, Prometheus, Grafana + **app**, **seed** e **connector-init** | ✅ `docker compose up -d --build` sobe tudo e o app fica `healthy`; conector `RUNNING` |
| `docker/seed/dev-seed.sql` | Titular + conta + chave EVP de dev (PII cifrada) | ✅ aplicado pelo serviço `seed`; gerador em `generate-dev-seed.js` |
| `docs/postman/*` | Collection (79 requisições) + ambiente local + gerador | ✅ `newman`: 0 falhas, repetível |
| `docker/mocks/mock_servers.py` | dict-gateway + core-ledger falsos (dev) | ✅ usado por `demo-flow.py` (ativação de chave, reserva/confirmação de saldo) |
| `scripts/demo-flow.py` · `simulate-spi.py` · `brcode.py` | Roteiro ponta a ponta (49 passos), simulador do SPI via Kafka/Avro, gerador de BR Code | ✅ 49/49 repetível |
| `scripts/chaos-test.py` | Teste de caos: core, Kafka, Debezium e Postgres fora ⇒ recuperação sozinha (banco fora ⇒ 503) | ✅ 17/17 |
| `scripts/validate-api.py` | Validação funcional de todos os endpoints (143 verificações) | ✅ 143/143 |
| `docker/debezium/outbox-connector.json` + `register-connector.sh` | Conector EventRouter | ✅ `DebeziumOutboxPipelineIT` (Postgres logical + Connect 3.0.8 + Kafka): roteamento, chave, bytes Avro e headers; o IT achou 2 defeitos (heartbeat STRUCT, `created_at`) já corrigidos. O `register-connector.sh` e o compose completo seguem sem `up` |
| `docker/keycloak/realm-pix.json` | Realm de dev: clients e escopos | ✅ importado no `up`; tokens emitidos, escopos e claim `pix_accounts` validados por `scripts/validate-api.py` |
| `.github/workflows/ci.yml` | `mvn verify` + dependency-check + Trivy | ⚠️ esboço, nunca executado (o `pom.xml` já existe; ajustar ao ativar) |

## Configuração (`.env`)
Portas, URLs, credenciais e caminhos ficam num único arquivo `.env` na raiz (modelo versionado: `.env.example`; o `.env` real é ignorado pelo git).
```bash
cp .env.example .env     # edite portas, caminhos, URLs do dict-gateway/ledger, chaves...
```
Quem lê o `.env`:
| Consumidor | Como |
|---|---|
| Aplicação | `application.yml` importa `./.env` sozinho (`spring.config.import`); variável do SO/IDE tem prioridade sobre o arquivo. Rode a partir da raiz do projeto |
| Docker Compose | `docker compose up -d --build` (lê o `.env` da raiz) (portas, credenciais do Postgres/Keycloak e caminhos montados) |
| `register-connector.sh` | lê o `.env` (ou `ENV_FILE=/outro/.env`); host/usuário/senha do banco e `CONNECT_URL` |

Segurança do Kafka/Schema Registry (TLS, SASL, Basic Auth): variáveis `KAFKA_SECURITY_PROTOCOL`, `KAFKA_SASL_*`, `KAFKA_SSL_*`, `SCHEMA_REGISTRY_USER_INFO` (vazias em dev); veja `subida-producao.md` §3.3.
Sem `.env`, valem os mesmos defaults do código e do compose. Em hml/prd não use arquivo: injete as variáveis pelo cofre/orquestrador.
O alvo de scrape em `ops/prometheus/prometheus.yml` é `app:8080` (container).

## Carga (Gatling)

`PixManagerSimulation` (`src/gatling/java`) cobre cobrança imediata e com vencimento, ordens de pagamento (com replay idempotente), chaves, limites e Pix recebidos, com os asserts do docs/08 §3.9 (p95 < 150 ms, falhas < 0,1%). Precisa da stack no ar (`docker compose up -d`, que já carrega o seed) e **nunca** deve rodar em ambiente compartilhado.

```bash
# fumaça (defaults: 20 rps, rampa 30 s, sustenta 60 s)
./mvn.sh -Pperf test-compile gatling:test -Dgatling.simulationClass=br.com.pixmanager.perf.PixManagerSimulation -Djacoco.skip=true
# perfil do docs/08: 300 rps, rampa 2 min, sustenta 15 min
./mvn.sh -Pperf test-compile gatling:test -Dgatling.simulationClass=br.com.pixmanager.perf.PixManagerSimulation -Djacoco.skip=true \
  -Dpix.rps=300 -Dpix.ramp.seconds=120 -Dpix.hold.seconds=900
```

Parâmetros opcionais: `pix.base-url`, `pix.token-url`, `pix.account-id`, `pix.key`, `pix.p95.ms`, `pix.fail.percent`. Relatório HTML em `target/gatling/`. A simulação não consulta o lag do Kafka (o docs/08 pede < 1000 ao final): olhe `kafka_consumergroup_lag` no Prometheus/Grafana.

## Fluxo de trabalho (Spec-Driven)
1. Mudança de comportamento começa por **FR/BR** em `02` (ID estável) + cenário Gherkin + linha na matriz.
2. Contrato (OpenAPI/Avro/DDL) alterado **antes** do código; diffs revisados no PR.
3. Teste escrito a partir do cenário (`@DisplayName("BR-… …")`).
4. Commit Conventional Commits (`feat(charge): …`), PR com checklist de `java-code-review`; `CHANGELOG` gerado do histórico.
5. Spec e código no **mesmo commit** quando o comportamento muda.

## Aviso regulatório
Valores regulatórios (prazos, limites, janelas de retentativa) vieram em parte **do solicitante** e em parte do OpenAPI; `docs/09` §4b marca o que foi **verificado (✅)** e o que **não foi (⚠️)**. Nada foi conferido no PDF do Regulamento/Manual de Tempos; valide antes de implementar. Os campos/padrões marcados **[V]** foram lidos do OpenAPI oficial da API Pix 2.10.0 e da página da API DICT 2.12.1.
