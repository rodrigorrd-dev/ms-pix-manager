# Subida em produção — ms-pix-manager

Objetivo: tudo que precisa estar configurado **antes** de subir em `prd` (vale igual para `hml`). Tudo aqui foi conferido no código; o que **não** foi validado em ambiente real está marcado com ⚠️.

> O `docker-compose.yml`, o `.env`, os `mocks`, o `seed`, o realm do Keycloak e todas as chaves/senhas do repositório são **só de desenvolvimento**. Nada disso vai para produção.

---

## 1. Checklist rápido

- [ ] Imagem construída e publicada (`Dockerfile`), rodando com `SPRING_PROFILES_ACTIVE=prd`
- [ ] PostgreSQL 16 com `wal_level=logical`, banco e usuários criados (§4)
- [ ] Kafka + Schema Registry no ar; **tópicos criados** e **schemas registrados** (§5) — em prd nada é criado automaticamente
- [ ] Kafka Connect com Debezium 3.0 e conector `pix-outbox-connector` registrado **depois** do 1º start do app (§6)
- [ ] **Segurança do Kafka** (TLS + SASL + ACLs) e do Schema Registry (§3.3)
- [ ] IdP (OAuth2) com `issuer`, `audience`, JWKS, escopos e o claim `pix_accounts` (§7)
- [ ] `dict-gateway`, `core-ledger` e `spi-gateway` **reais** acessíveis (§8)
- [ ] Todas as variáveis obrigatórias definidas, segredos no cofre (§3)
- [ ] TLS/mTLS terminado no gateway/ingress; o ingress publica só a porta da API — o actuator fica na **porta de gerenciamento** (§9)
- [ ] Pool de conexões do banco dimensionado e **medido** (§3.2, `docs/load-test-results.md`)
- [ ] Canal de OTP (SMS/e-mail) configurado, se chaves de e-mail/telefone forem permitidas (§8)
- [ ] **Webhook:** `PIX_WEBHOOK_SIGNING_KEY` no cofre e combinada com cada cliente; saída (egress) controlada por firewall/proxy; `INSECURE_TLS` e `ALLOW_PRIVATE_HOSTS` **desligados** (§3.2, §8)
- [ ] Prometheus, regras de alerta, dashboard e OTLP apontados; **runbooks** lidos pelo plantão (§10, `docs/runbooks`)
- [ ] Rotação de chave ensaiada em hml (`docs/runbooks/rotacao-de-chave.md`) e plano de backup/DR aprovado (`docs/runbooks/backup-e-dr.md`)
- [ ] Valores regulatórios de `pix.policy.*` validados pelo compliance (§12, `docs/regulatory-verification.md`)
- [ ] Varreduras de segurança repetidas na versão a publicar (`docs/security-scan.md`)
- [ ] Smoke test da §11 executado

---

## 2. Dependências externas (o que precisa existir)

| Dependência | Versão testada | Observação |
|---|---|---|
| PostgreSQL | 16 | `wal_level=logical` (Debezium) |
| Kafka | Confluent 7.7 (KRaft) | `auto.create.topics.enable=false` recomendado |
| Schema Registry | 7.7, compat `BACKWARD_TRANSITIVE` | Basic Auth suportado via `SCHEMA_REGISTRY_USER_INFO` (§3.3); ⚠️ não testado contra um registry autenticado |
| Kafka Connect + Debezium | 3.0 (`quay.io/debezium/connect:3.0`) | conector de outbox |
| IdP OAuth2/OIDC | qualquer um com JWKS (RS256/ES256) | em dev é Keycloak 26; o app só **valida** o JWT |
| `dict-gateway` | **sistema externo** | contrato abaixo, §8 |
| `core-ledger` | **sistema externo** | contrato abaixo, §8 |
| `spi-gateway` | **sistema externo** | fala Kafka (Avro) com o app |
| OTel Collector / Prometheus | opcional | tracing e métricas |

---

## 3. Variáveis de ambiente

Profile: `SPRING_PROFILES_ACTIVE=prd` (liga log JSON, fecha Swagger/Springwolf, desliga auto-registro de schema, amostragem de trace 10% e move o actuator para a **porta de gerenciamento**, §9).

### 3.1 Obrigatórias (sem elas o app **não sobe** ou nasce inseguro)

| Variável | Valor | Por quê |
|---|---|---|
| `PIX_ISPB` | 8 dígitos do ISPB da instituição | valida no start; vai em todos os eventos |
| `DB_URL` | `jdbc:postgresql://host:5432/db?sslmode=require` | o default é `localhost` |
| `DB_USERNAME` / `DB_PASSWORD` | usuário e senha (cofre) | `DB_PASSWORD` não tem default |
| `PIX_CRYPTO_AES_KEY` | 32 bytes em Base64 | AES-256-GCM da PII; fail-fast |
| `PIX_CRYPTO_HMAC_KEY` | ≥32 bytes em Base64, **diferente** da AES | índice cego do documento/chave; fail-fast |
| `OAUTH2_JWKS_URI` | URL do JWKS do IdP | sem ela não há validação de JWT |
| `OAUTH2_ISSUER_URI` | `iss` esperado | **obrigatório em hml/prd** (o app recusa subir sem) |
| `OAUTH2_AUDIENCE` | `aud` esperado | **obrigatório em hml/prd** |
| `PIX_WEBHOOK_SIGNING_KEY` | ≥32 bytes em Base64 (`openssl rand -base64 32`) | assina cada webhook enviado (`x-pix-signature`); **o app não sobe sem ela** em hml/prd (em dev o perfil `local` tem uma chave de exemplo) |
| `KAFKA_BOOTSTRAP_SERVERS` | `broker1:9093,broker2:9093` | default é `localhost` |
| `SCHEMA_REGISTRY_URL` | URL do registry | default é `localhost` |
| `DICT_GATEWAY_URL` | URL do dict-gateway | default `localhost:18091` |
| `CORE_LEDGER_URL` | URL do core-ledger | default `localhost:18090` |

Gerar as chaves (guarde no cofre/KMS, **nunca** no repositório):
```bash
openssl rand -base64 32     # PIX_CRYPTO_AES_KEY
openssl rand -base64 32     # PIX_CRYPTO_HMAC_KEY   (tem que ser outro valor)
```
**Rotação da chave AES: suportada** (chaveiro de versões + re-criptografia em lote; `docs/runbooks/rotacao-de-chave.md`, testado por `PiiReencryptionIT`). Guarde **todas as versões** da AES enquanto houver backup do banco ou linha ainda não migrada: perder uma chave = perder o dado cifrado com ela. Variáveis: `PIX_CRYPTO_KEY_VERSION`, `PIX_CRYPTO_PREVIOUS_AES_KEYS_V1…V3`, `PIX_CRYPTO_REENCRYPT`. ⚠️ A chave **HMAC** (índice cego) **não** tem rotação: trocá-la quebra a busca por documento/chave (veja o runbook).

### 3.2 Ajustar (têm default de desenvolvimento)

| Variável | Default | Em produção |
|---|---|---|
| `PIX_QR_BASE_URL` | `pix.example.com/qr/v2/` | host/caminho **real** do payload do QR dinâmico (vai no `location` das cobranças) |
| `PIX_ERROR_BASE_TYPE_URI` | `https://errors.pixmanager.example/` | URL pública da sua documentação de erros (campo `type` do problem+json) |
| `PIX_INSTITUTION_NAME` | `Instituição` | nome da instituição |
| `KAFKA_AUTO_REGISTER_SCHEMAS` | `false` (prd) | manter `false` |
| `KAFKA_AUTO_CREATE_RETRY_TOPICS` | `false` | manter `false` e **criar os tópicos de retry** (§5) |
| `PIX_DOCS_PUBLIC` / `SWAGGER_ENABLED` | `false` | manter `false` |
| `SPRINGWOLF_ENABLED` | `true` | recomendado `false` (já fica fechado por `PIX_DOCS_PUBLIC=false`) |
| `OTLP_TRACES_ENDPOINT` | `http://localhost:14318/v1/traces` | endpoint do collector |
| `TRACING_SAMPLING` | `0.1` (prd) | ajustar ao volume |
| `MANAGEMENT_ENDPOINTS` | `health,info,prometheus` | manter; não exponha (§9) |
| `SERVER_PORT` | `8088` (o compose força `8080` dentro do contêiner) | porta da API; é a única que o ingress publica |
| `MANAGEMENT_SERVER_PORT` | `8081` (hml/prd; em dev o actuator fica na porta da API) | porta interna de health/info/prometheus (§9) |
| `DB_POOL_MAX` / `DB_POOL_MIN_IDLE` | `10` / `2` no `application.yml`; compose/dev usa `30` / `5` | **meça**: em carga de ~900 req/s o pool de 10 saturou (187 requisições esperando, p95 284 ms); com 30, p95 65 ms. Regra: `pool × réplicas ≤ max_connections − reserva` (jobs, Debezium, ferramentas) e `hikaricp_connections_pending` em 0 (`docs/load-test-results.md`) |
| `PIX_OUTBOX_RETENTION` / `PIX_OUTBOX_CLEANUP_DELAY` | `P7D` / `PT10M` | linhas da `outbox_event` mais antigas que isto são apagadas (o Debezium lê o WAL, não a tabela). Também é o alcance da republicação (`docs/runbooks/backup-e-dr.md` §4.1) |
| `PIX_METRICS_STUCK_AFTER` | `PT5M` | quanto tempo sem avançar uma ordem/chave/reserva conta como **presa** nas métricas/alertas |
| `KAFKA_RETRY_PARTITIONS` | `12` | partições dos tópicos `-retry-N` (≥ as do principal); só vale com `KAFKA_AUTO_CREATE_RETRY_TOPICS=true` — em prd crie os tópicos à mão (§5) |
| `PIX_OTP_SENDER` / `PIX_OTP_GATEWAY_URL` | `none` / vazio | `http` + URL do serviço de notificação para a prova de posse de e-mail/telefone (§8). `none` = padrão **seguro** (a prova de posse responde 503). **Nunca** `log` em hml/prd (escreve o código no log) |
| `PIX_QR_TRUST_STORE_PATH` / `_PASSWORD` / `_TYPE` | arquivo PKCS12 (ou JKS) com a cadeia **ICP-Brasil** | **Obrigatória para pagar QR dinâmico.** Sem ela o ms recusa todo QR dinâmico (falha fechada, PIX-422-018). Revogação (CRL/OCSP) não é verificada |
| `PIX_QR_ALLOW_UNVERIFIED_CHAIN` / `PIX_QR_INSECURE_TLS` | `false` | **Mantenha `false`.** `true` aceita JWS sem validar a cadeia / TLS autoassinado (só dev; o perfil `local` liga) |
| `PIX_SCHEDULE_*` (`MAX_HORIZON_DAYS` 730, `MAX_LATE_DAYS` 3, `MAX_TECHNICAL_ATTEMPTS` 5, `DISPATCH_DELAY` PT30S, `BATCH` 20) | — | Pix Agendado. Valores de projeto; `MAX_LATE_DAYS`: sistema parado além disso ⇒ a ocorrência vira `FAILED(MISSED)` (não paga fora de hora) |
| `PIX_AUTOMATIC_*` (`MIN/MAX_NOTICE_DAYS` 2/10, `RETRY_DAYS` 3, `MAX_LATE_DAYS` 1, `REQUEST_VALIDITY` P7D, `GATEWAY_URL`) | — | Pix Automático (pagador). **Prazos NÃO confirmados no regulamento: valide com o compliance antes de ligar.** `GATEWAY_URL` padrão = dict-gateway |
| `PIX_WEBHOOK_INSECURE_TLS` / `PIX_WEBHOOK_ALLOW_PRIVATE_HOSTS` | `false` | **mantenha `false`.** `true` aceita certificado autoassinado do receptor / hosts internos (desliga o anti-SSRF); existem só para dev e testes (o perfil `local` liga os dois) |
| `PIX_WEBHOOK_DISPATCH_BATCH` / `_DISPATCH_DELAY` / `_CONNECT_TIMEOUT` / `_REQUEST_TIMEOUT` | `20` / `PT2S` / `3s` / `5s` | vazão do envio: sequencial, então `lote × timeout` limita o tempo de um ciclo; aumente o lote com receptores rápidos |
| `pix.policy.otp.*` | 5 min / 5 tentativas / 60 s / 10 por dia | parâmetros **de projeto** do OTP (não são do regulamento) |
| `DB_CONNECTION_TIMEOUT_MS` | `3000` | ok |
| `KAFKA_CONSUMER_GROUP` | `ms-pix-manager` | ok (os 3 consumers usam `ms-pix-manager.<fluxo>`) |
| `KAFKA_RETRY_ATTEMPTS` / `_DELAY_MS` / `_MULTIPLIER` / `_MAX_DELAY_MS` | `4` / `5000` / `6` / `180000` | backoff dos consumers antes da DLT |
| `DICT_*_TIMEOUT`, `CORE_LEDGER_*_TIMEOUT` | connect 500 ms, read 3 s / 2 s | ajustar ao SLA dos gateways |
| `DICT_SWEEPER_DELAY`, `CORE_LEDGER_SWEEPER_DELAY` | `PT3S` / `PT2S` | intervalo dos jobs de ativação de chave e de reserva |
| `JPA_DDL_AUTO` | `validate` | **não mude** (o schema é do Liquibase) |
| `LIQUIBASE_ENABLED` | `true` | ver §4 |

### 3.3 Segurança do Kafka e do Schema Registry (recomendado em produção)

**Recomendação: em prd use `SASL_SSL` (TLS + usuário/senha) e ACLs.** O tópico `pix.spi.payment-settled.v1` leva o CPF/CNPJ do pagador **em claro** (contrato: "rede interna, ACL restrita, retenção 7 d"), e quem consegue publicar em `pix.spi.payment-status.v1` consegue marcar um pagamento como liquidado. PLAINTEXT só é aceitável em rede privada isolada **e** com aprovação do time de segurança.

O app não monta clientes Kafka à mão: tudo (consumer, producer, retry/DLT, admin) usa a configuração padrão do Spring, então a segurança é **só configuração**, sem mudar código. Defaults = dev (`PLAINTEXT`, sem credenciais).

| Variável | Para quê | Exemplo |
|---|---|---|
| `KAFKA_SECURITY_PROTOCOL` | `PLAINTEXT` \| `SSL` \| `SASL_PLAINTEXT` \| `SASL_SSL` | `SASL_SSL` |
| `KAFKA_SASL_MECHANISM` | mecanismo SASL | `SCRAM-SHA-512` |
| `KAFKA_SASL_JAAS_CONFIG` | usuário/senha do app (**cofre**) | `org.apache.kafka.common.security.scram.ScramLoginModule required username="pixapp" password="***";` |
| `KAFKA_SSL_TRUSTSTORE_LOCATION` / `_PASSWORD` / `_TYPE` | truststore da CA **privada** (só com o profile `kafka-tls`) | `file:/certs/truststore.jks` / `***` / `JKS` |
| `KAFKA_SSL_KEYSTORE_LOCATION` / `_PASSWORD` / `_TYPE`, `KAFKA_SSL_KEY_PASSWORD` | certificado **de cliente**, só se o broker exigir mTLS (profile `kafka-mtls`) | `file:/certs/keystore.jks` |
| `SCHEMA_REGISTRY_USER_INFO` | Basic Auth do registry, `usuario:senha` (vazio = sem autenticação) | `pixapp:***` |

Como ativar:
- **CA pública** (broker gerenciado com certificado de CA conhecida): só `KAFKA_SECURITY_PROTOCOL=SASL_SSL`, `KAFKA_SASL_MECHANISM` e `KAFKA_SASL_JAAS_CONFIG`. A JVM usa o truststore padrão.
- **CA privada**: some `SPRING_PROFILES_ACTIVE=prd,kafka-tls` e as variáveis `KAFKA_SSL_TRUSTSTORE_*`. Para mTLS, `prd,kafka-tls,kafka-mtls` e as `KAFKA_SSL_KEYSTORE_*` + `KAFKA_SSL_KEY_PASSWORD`.
- Os profiles `kafka-tls`/`kafka-mtls` **recusam subir** se faltar uma variável (a mensagem nomeia qual). Isso é proposital: o Spring sozinho deixaria o `${...}` literal e o erro só apareceria, obscuro, ao criar o primeiro consumer.
- Montar o truststore/keystore como arquivo no container (secret/volume somente leitura); as senhas vêm do cofre, **nunca** de arquivo versionado. O `.env.example` traz todas as variáveis (vazias).

**ACLs sugeridas** (princípio do menor privilégio; ⚠️ **não testadas** — ajuste ao seu Kafka/autorizador):
```bash
ADM="--bootstrap-server broker:9093 --command-config admin.properties --add"
# app (User:pixapp): lê os 3 tópicos de entrada e seus retry; escreve retry e DLT; grupos ms-pix-manager.*
for t in pix.spi.payment-settled.v1 pix.spi.payment-status.v1 pix.spi.refund-status.v1; do
  kafka-acls $ADM --allow-principal User:pixapp --operation Read --operation Describe --topic $t
  for r in $t-retry-0 $t-retry-1 $t-retry-2; do kafka-acls $ADM --allow-principal User:pixapp --operation Read --operation Write --operation Describe --topic $r; done
  kafka-acls $ADM --allow-principal User:pixapp --operation Write --operation Describe --topic $t.dlt
done
kafka-acls $ADM --allow-principal User:pixapp --operation Read --group ms-pix-manager --resource-pattern-type prefixed
# Kafka Connect/Debezium (User:debezium): só escreve os 5 tópicos de saída (+ tópicos internos _connect_* e o grupo do Connect)
for t in charge key payment received refund; do kafka-acls $ADM --allow-principal User:debezium --operation Write --operation Describe --topic pix.$t.v1; done
# spi-gateway (User:spigateway): escreve os 3 tópicos de entrada; lê pix.payment.v1 e pix.refund.v1
```
O **Kafka Connect** tem a própria configuração de segurança (variáveis `CONNECT_*` da imagem Debezium para o worker e `producer.override.*` no conector): ⚠️ não coberto por este repositório, consulte a documentação da imagem.

**O que foi e o que não foi testado** (broker de teste em contêiner):
- ✅ `SASL_PLAINTEXT` com `PLAIN`: com senha **errada** o app recebe `Failed authentication`; com a certa conecta, o admin cria os tópicos de retry/DLT e os 3 consumers entram nos grupos.
- ✅ Profiles `kafka-tls`/`kafka-mtls`: as propriedades chegam ao cliente Kafka (`security.protocol`, `ssl.truststore.*`, `ssl.keystore.*`) e, sem as variáveis, o app sai com erro claro (teste unitário `KafkaTlsGuardTest` + execução real).
- ⚠️ **Não comprovado:** o handshake **TLS** de ponta a ponta (`SSL`/`SASL_SSL`) — o broker de teste com TLS não subiu no meu ambiente por problema da PKI de teste, não do app —, `SCRAM`, mTLS, as ACLs acima e o registry com Basic Auth. Faça esse teste em **hml** antes de produção.

---

## 4. Banco de dados (PostgreSQL 16)

1. Parâmetros do servidor: `wal_level=logical`, `max_replication_slots ≥ 4`, `max_wal_senders ≥ 4`. Recomendado `max_slot_wal_keep_size` para o slot do Debezium não encher o disco se o Connect parar (há alerta `PixOutboxSlotLag`).
2. Crie o banco e um **usuário da aplicação** dono do schema (o Liquibase roda no start do app e cria 39 tabelas (incluindo as de controle do Liquibase e do ShedLock), funções, uma view materializada e a publicação `pix_outbox_pub`). Mesmo usuário precisa de `CREATE` no banco.
3. Crie um **usuário do Debezium** separado, com `REPLICATION` e `SELECT` em `outbox_event` (em vez de reutilizar o da aplicação):
   ```sql
   CREATE ROLE debezium LOGIN REPLICATION PASSWORD '<cofre>';
   GRANT SELECT ON TABLE outbox_event TO debezium;
   ```
4. Sem `init` de dados: **não rode o seed** (`docker/seed`) em produção. Contas e titulares vêm do core bancário (decisão A2); este serviço não os cadastra. ⚠️ Hoje a **única** forma de popular `account_holder`/`payment_account` é SQL direto ou integração com o core — defina como essa carga chega ao banco antes do go-live.
5. A `outbox_event` é limpa pelo app (`OutboxCleanupJob`, retenção `PIX_OUTBOX_RETENTION`): sem isso ela cresceria sem limite. Backup/PITR e retenção por conta do time de dados — plano em `docs/runbooks/backup-e-dr.md`. PII (documento, chave) fica **cifrada** no banco (AES-GCM) com índice cego (HMAC).
6. Migrações: o app aplica no start. Em produção prefira rodar o Liquibase uma vez no pipeline (`LIQUIBASE_ENABLED=false` nas réplicas) para não depender de qual réplica sobe primeiro. ⚠️ Esse modo "migrar à parte" não foi exercitado.

---

## 5. Kafka e Schema Registry

Em `prd` o app **não cria** tópicos nem registra schemas. Crie antes.

### 5.1 Tópicos
| Tópico | Quem escreve | Partições (ref. dev) | Retenção |
|---|---|---|---|
| `pix.charge.v1`, `pix.received.v1`, `pix.refund.v1`, `pix.payment.v1`, `pix.key.v1` | Debezium (outbox) | 12 (key: 6) | 7 d |
| `pix.spi.payment-settled.v1`, `pix.spi.payment-status.v1` | spi-gateway | 12 | 7 d |
| `pix.spi.refund-status.v1` | spi-gateway | 6 | 7 d |
| `<cada tópico acima>.dlt` | app / Spring | 3 | 30 d |
| `<tópico de entrada>-retry-0`, `-retry-1`, `-retry-2` (3 tópicos de entrada ⇒ 9) | app (retry não bloqueante) | **mesmas partições do principal** | curta |

- Os 5 tópicos de saída são os que existem hoje no código (`charge`, `key`, `payment`, `received`, `refund`); `docker-compose.yml` cria outros (`med`, `schedule`, `automatic`, `limit`, `dict.key-event`, `spi.automatic-*`, `spi.med-*`) para módulos **ainda não implementados**: não são necessários agora.
- Os tópicos de retry precisam ter **≥ partições do principal** (o retry publica na mesma partição). O app agora pede `KAFKA_RETRY_PARTITIONS=12` quando os cria (verificado em dev: 12 partições; antes saíam com 1). Em prd crie-os à mão com o mesmo número do principal. ⚠️ Roteamento de retry sob falha real com várias partições não foi exercitado.
- Fator de replicação ≥ 3 e `min.insync.replicas=2` em produção (o dev usa 1).
- Consumers: `isolation.level=read_committed`, `enable.auto.commit=false`, `auto.offset.reset=earliest`. Producer do app: `acks=all`, idempotente.

### 5.2 Schemas (Avro)
Subjects (estratégia por tópico, `<tópico>-value`) que o app precisa **já registrados** para **publicar** (o outbox usa `use.latest.version`):
`pix.charge.v1-value`, `pix.key.v1-value`, `pix.payment.v1-value`, `pix.received.v1-value`, `pix.refund.v1-value`.
Os subjects de entrada (`pix.spi.*-value`) são do **spi-gateway** (o app só decodifica pelo id do schema). Fontes: `src/main/resources/avro/*.avsc`.

Para promover do ambiente de dev/CI (onde o app registrou sozinho) para o de produção:
```bash
SRC=http://registry-dev:8081; DST=https://registry-prd:8081
for s in charge key payment received refund; do
  curl -s $SRC/subjects/pix.$s.v1-value/versions/latest | jq '{schema}' \
   | curl -s -X POST -H 'Content-Type: application/vnd.schemaregistry.v1+json' -d @- $DST/subjects/pix.$s.v1-value/versions
done
```
Configure a compatibilidade global: `curl -X PUT -d '{"compatibility":"BACKWARD_TRANSITIVE"}' -H 'Content-Type: application/vnd.schemaregistry.v1+json' $DST/config`.

⚠️ **Contratos com o spi-gateway:** os `.avsc` de entrada estão marcados no repositório como "contrato a confirmar" (`docs/10`, pendência E2). Confirme com quem mantém o spi-gateway antes do go-live.

---

## 6. Debezium (outbox → Kafka)

- Imagem: `quay.io/debezium/connect:3.0` (fixe a tag exata, ex. `3.0.8.Final`). Conversores: chave `StringConverter`, valor `ByteArrayConverter` (os bytes Avro seguem intactos).
- O conector é `docker/debezium/outbox-connector.json`. **Troque no JSON/variáveis** `database.hostname`, `database.user` (`debezium`), `database.password`, `database.dbname` — o arquivo traz os valores de dev. Slot `pix_outbox_slot`, publicação `pix_outbox_pub` (criada pelo Liquibase, `publication.autocreate.mode=disabled`), tabela `public.outbox_event`, `snapshot.mode=no_data`, heartbeat 10 s.
- **Ordem:** só registre o conector **depois** que o app subiu e migrou o banco (a tabela e a publicação precisam existir):
  ```bash
  CONNECT_URL=https://connect-prd:8083 POSTGRES_USER=debezium POSTGRES_PASSWORD=*** POSTGRES_DB=pixmanager \
  CONNECTOR_DB_HOST=db-prd CONNECTOR_DB_PORT=5432 ./docker/debezium/register-connector.sh
  curl -s $CONNECT_URL/connectors/pix-outbox-connector/status      # RUNNING
  ```
- Kafka Connect em produção precisa de autenticação/TLS próprios e tópicos internos (`_connect_*`) com replicação ≥ 3 (o dev usa 1).
- Monitore o lag do slot (`PixOutboxSlotLag`) e o status do conector.

---

## 7. Identidade e autorização (OAuth2)

O app é **Resource Server**: valida assinatura (JWKS, `RS256`/`ES256`), `iss`, `aud`, `exp`/`nbf`. Não emite token.

1. **Issuer** e **audience** do IdP real em `OAUTH2_ISSUER_URI` / `OAUTH2_AUDIENCE`. O token tem que trazer exatamente esse `aud`.
2. **Escopos** (claim `scope`, prefixo `SCOPE_`). Crie e atribua conforme o cliente:
   - API BCB (`/api/pix/v2`): `cob.read`, `cob.write`, `cobv.read`, `cobv.write`, `pix.read`, `pix.write`, `webhook.read`, `webhook.write`, `payloadlocation.read`, `payloadlocation.write`
   - Gestão (`/v1`): `pixkey.read/write`, `payment.read/write`, `limit.read/write`, `med.read/write`, `report.read`, `webhook.admin` (reenviar entregas de webhook; só back-office com `pix_accounts=*`)
   - `pii.document.read`: quem pode ver o **documento completo** (sem ele, o documento sai mascarado)
3. **Claim `pix_accounts`** (obrigatório para ver/operar contas): lista de UUIDs das contas que o cliente acessa, ou `"*"` para todas (back-office). **Sem o claim o cliente não enxerga nenhuma conta.** O IdP precisa carregar esse vínculo cliente → contas (premissa G1 em `docs/10`); em dev isso é um mapper fixo no realm, em produção tem que vir da sua fonte de verdade.
4. Não use o realm de dev (`docker/keycloak/realm-pix.json`, secret `dev-secret-local-only`).

---

## 8. Sistemas externos (contratos que o app espera)

| Sistema | O app chama | Falha técnica (timeout/429/5xx) | Falha de negócio (4xx) |
|---|---|---|---|
| **dict-gateway** | `POST /dict/v1/entries` (registrar), `POST /dict/v1/entries/deletion`, `POST /dict/v1/entries/lookup` (404 = chave inexistente) | retry; circuit breaker `dict-gateway` | recusa definitiva, sem retry |
| **core-ledger** | `POST /ledger/v1/reservations`, `…/{idempotencyKey}/confirmation`, `…/cancellation` (header `Idempotency-Key`; resposta `{ "ledgerRef": "…" }`; recusa `{ "code": "INSUFFICIENT_FUNDS"\|"ACCOUNT_BLOCKED", "message": … }`) | retry; circuit breaker `core-ledger` | recusa definitiva |
| **Serviço de notificação (OTP)** | `POST /otp/v1/messages` com `{channel: SMS\|EMAIL, destination, code, validForSeconds}` (header `X-Request-Id`); 2xx = aceito | retry pelo cliente (502/503); circuit breaker `otp-gateway` | só **400/422** = destino inválido (negócio); 404/405/etc. = configuração errada ⇒ técnico. ⚠️ Contrato **inventado por este projeto**, a alinhar com o serviço real; em TLS obrigatório (o código trafega no corpo) |
| **Receptores de webhook (clientes)** | `POST <webhookUrl>/pix` (HTTPS), assinado (`x-pix-timestamp`, `x-pix-signature`); 2xx = entregue | retry com backoff até 8 tentativas ⇒ `DEAD` | — (qualquer não-2xx é falha). O destino é **entrada do cliente**: o app bloqueia hosts internos e metadados de nuvem (anti-SSRF) e não segue redirecionamentos, mas **isto não substitui um controle de saída na rede**: restrinja o egress do pod (firewall/proxy) e monitore os destinos. ⚠️ A checagem de DNS tem janela de *rebinding* (`docs/05`) |
| **dict-gateway — MED** | `POST /dict/v1/funds-recoveries`, `POST …/{id}/cancellation` (REPORTER) | circuit breaker `dict-gateway`; técnica ⇒ 503 | **Contrato RASCUNHO**: formalize com o gateway real |
| **spi-gateway — Pix Automático** | `POST /spi/v1/automatic/authorizations/{idRec}/approval\|rejection\|cancellation` | idem; a decisão fica gravada e o recebedor pode ser reavisado repetindo | **Contrato RASCUNHO**. A solicitação e a cobrança chegam hoje por API (`/v1/automatic-authorizations`), não por Kafka |
| **PSP do recebedor — QR dinâmico** | `GET https://<url do QR>` (payload JWS) | timeouts 3 s/5 s, sem redirecionamento, ≤ 64 KB | A URL vem do usuário: o **egress do pod** deve ser restringido (anti-SSRF é defesa em profundidade, não substitui) |
| **spi-gateway** | via Kafka (§5): consome `pix.spi.payment-settled/payment-status/refund-status.v1`; recebe `pix.payment.v1` e `pix.refund.v1` | — | — |

- A especificação completa de cada contrato é o código em `infrastructure/rest/dict` e `infrastructure/rest/ledger`; o comportamento de referência em dev é o `docker/mocks/mock_servers.py`. ⚠️ O ms **nunca foi testado contra os gateways reais**; o contrato é "a confirmar" (`docs/10` E2).
- TLS/mTLS com DICT e SPI é responsabilidade desses gateways (decisão A1), não do ms.

---

## 9. Imagem, execução e rede

- Build: `docker build -t <registry>/ms-pix-manager:<tag> .` (multi-stage, JRE 21, usuário sem privilégios, `-XX:MaxRAMPercentage=75`). Os testes **não** rodam no build: rode `./mvn.sh verify` no pipeline antes.
- Portas: API em `SERVER_PORT` (HTTP) e actuator em `MANAGEMENT_SERVER_PORT` (8081 em hml/prd). Termine **TLS (e mTLS, se o BCB/parceiros exigirem)** no gateway/ingress; o app só confia em JWT válido e deve ficar em rede privada (NetworkPolicy).
- Probes: `liveness` = `/actuator/health/liveness`, `readiness` = `/actuator/health/readiness`. `server.shutdown=graceful`: dê `terminationGracePeriodSeconds` ≥ 30 s.
- **Actuator em porta separada (verificado com o perfil `prd`):** na porta da API `/actuator/*` responde **404** e Swagger/OpenAPI **401**; `health`, `readiness` e `prometheus` respondem **200 só na porta de gerenciamento**. O ingress publica **somente** a porta da API; Prometheus, probes do orquestrador e plantão acessam a de gerenciamento por rede interna (NetworkPolicy). Esses endpoints continuam sem autenticação (`SecurityConfig`), então **não** exponha a porta 8081.
- Réplicas: pode escalar horizontalmente. Os jobs (`PendingDictKeysJob`, `PendingReservationJob`) usam **ShedLock** (tabela `shedlock`), então só uma réplica roda cada job por vez. Consumers Kafka dividem partições pelo grupo.
- Dimensionamento: ponto de partida de 1 vCPU / 1 GiB por réplica, 2 réplicas, e **meça** em hml. Teste de carga (Gatling) executado em máquina de desenvolvimento compartilhada: ~236 req/s com p95 de 9 ms e ~905 req/s com p95 de 65 ms (pool de 30), com o gargalo seguinte sendo o mock do DICT — veja `docs/load-test-results.md` para o que isso **não** prova. Testes de caos (core, Kafka, Debezium, Postgres) em `scripts/chaos-test.py`.
- Fuso: o banco e o JDBC usam UTC; regras de negócio de cobrança usam `America/Sao_Paulo` no código.

---

## 10. Observabilidade

- **Métricas:** Prometheus raspa `/actuator/prometheus` **na porta de gerenciamento** (ajuste o alvo em `ops/prometheus/prometheus.yml`; o do compose é `app:8080` do dev). Além das métricas padrão (HTTP, JVM, Hikari, Resilience4j), o app expõe métricas de operação: `pix_payment_orders_stuck{status}`, `pix_pixkeys_pending_dict_stuck`, `pix_ledger_reservations_pending_stuck`, `pix_outbox_rows`, `pix_outbox_slot_lag_bytes` (-1 = sem permissão para ler o WAL: use o `postgres_exporter`) e `pix_dlt_messages_total{topic}`. Lag de consumer vem do `kafka-exporter`.
- **Alertas:** `ops/prometheus/alert-rules.yml` (18 regras, validadas com `promtool check rules` e **testadas** com `promtool test rules` em `ops/prometheus/tests`): app fora do ar, circuit breaker aberto, pagamento preso, chave presa em `PENDING_DICT`, reserva presa, mensagem na DLT, lag de consumer, slot do Debezium, backlog da outbox, p95, taxa de 5xx pool saturado e entregas de webhook DEAD/vencidas. Cada alerta aponta para um **runbook** em `docs/runbooks`. Antes desta revisão `PixDltMessages` e `PixOutboxSlotLag` referenciavam métricas que **não existiam** (nunca disparariam): foi corrigido. `PixPaymentsStuck` dispara em dev por desenho (sem spi-gateway toda ordem fica `RESERVED`).
- **Dashboard:** `ops/grafana/dashboards/pix-manager.json` (provisionado com os datasources Prometheus/Tempo/Loki em `ops/grafana/provisioning`).
- **Traces:** OTLP HTTP para `OTLP_TRACES_ENDPOINT`. Todo erro da API devolve `traceId`.
- **Logs:** JSON no stdout (perfil `prd`), com **PII mascarada**. O app não envia logs a nenhum backend: colete com o agente da plataforma. O Promtail do compose (lê o socket do Docker) é **só dev**.
- ⚠️ Os alertas foram testados como regras, mas **nunca dispararam em produção**: ajuste limiares (5 min de "preso", 512 MB de slot, 1% de 5xx) à realidade e ligue o Alertmanager ao canal do plantão.

---

## 11. Ordem de subida e smoke test

1. Postgres → Kafka/Schema Registry (tópicos e schemas prontos) → IdP.
2. **App** (migra o banco) — espere `/actuator/health/readiness` = `UP`.
3. **Conector Debezium** (§6).
4. Libere o tráfego do ingress.

Smoke test pós-deploy (com um token real do IdP):
```bash
curl -s https://<host>/actuator/health/readiness                       # (de dentro da rede) {"status":"UP"}
curl -s -o /dev/null -w "%{http_code}\n" "https://<host>/v1/pix-keys?accountId=<uuid>"          # 401 sem token
curl -s -H "Authorization: Bearer $TOKEN" "https://<host>/v1/pix-keys?accountId=<uuid>"         # 200
curl -s https://<connect>/connectors/pix-outbox-connector/status | jq .connector.state          # "RUNNING"
```
Depois crie um registro de teste que gere evento (ex.: uma cobrança em conta de teste) e confirme a mensagem em `pix.charge.v1`. ⚠️ O repositório tem `scripts/validate-api.py` e `scripts/demo-flow.py`, mas eles dependem do **seed e dos mocks** de dev: **não os rode em produção**.

---

## 12. Pendências e riscos conhecidos (leia antes do go-live)

Estado depois das rodadas de carga, caos, segurança e funcionalidades. **Feito e provado** está no fim; o que segue aberto está na tabela.

### Aberto
| Item | Situação |
|---|---|
| Valores regulatórios (`pix.policy.*`) | Cruzados com fontes públicas do BCB em `docs/regulatory-verification.md`: ✅ limite noturno, 90 dias de devolução, 80 dias do MED, 30º dia da reivindicação, expiração padrão; 🟡 limites de dispositivo não cadastrado, 7 dias/72 h do MED, janela 18h–21h; ❌ `med.tracking-window` 90 d, doador 7 d, chaves por conta 5/20; 🏢 limites/tetos por dia são decisão da instituição. **Falta o parecer do compliance** sobre o texto vigente |
| Contratos com spi-gateway, dict-gateway, core-ledger e serviço de notificação | "A confirmar" (`docs/10` E2); só testados contra mocks e contra WireMock (contrato de erro/status). O contrato do **OTP** foi definido por este projeto |
| Carga de contas/titulares | Sem API; precisa de integração com o core ou carga por SQL |
| **Funcionalidades parciais** | QR dinâmico, Pix Agendado, MED e Pix Automático **existem**, mas: (a) os gateways do MED e do Pix Automático são contratos **rascunho**; (b) solicitações/notificações do SPI chegam por API, **não** por Kafka; (c) MED sem rastreamento em camadas; (d) **prazos do Pix Automático não confirmados** no regulamento; (e) QR dinâmico exige trust store ICP-Brasil e não verifica revogação. Detalhes em `docs/pendencias-funcionais.md` |
| Segurança do transporte do Kafka | SASL `PLAIN` provado; o handshake **TLS**, SCRAM, mTLS, ACLs e o registry com Basic Auth têm configuração pronta, mas **nunca rodaram** contra um sistema real (§3.3) |
| HMAC (índice cego) sem rotação | Ver `docs/runbooks/rotacao-de-chave.md` |
| Spring Boot 4 | 2 CVE CRITICAL do `spring-webmvc` só têm correção no Spring Framework 7; **não alcançáveis** por este código (sem views/SSE), aceitas conscientemente — migrar para o Boot 4 (`docs/security-scan.md`, pendência E1) |
| Pentest, threat model, CodeQL no GitHub, OWASP dependency-check | Não realizados / não executados (`docs/security-scan.md`) |
| `ci.yml` | Validado com `actionlint`, **nunca executado no GitHub**; não há *deploy* (depende da plataforma) |
| Carga em ambiente dedicado, soak (horas), multi-réplica | Não feitos (`docs/load-test-results.md`) |
| Republicação da outbox | Procedimento por SQL testado em dev; sem ferramenta/endpoint (`docs/runbooks/backup-e-dr.md`) |
| Backup, restauração e DR | **Plano escrito, nunca ensaiado**; RPO/RTO são propostas |
| Runbooks | Escritos a partir do código/dev; marcados com ⚠️ o que não foi executado em ambiente real |

### Feito e provado (com a evidência)
- Suíte completa (`./mvn.sh verify`): testes unitários + ITs com Testcontainers, 0 falhas, cobertura do `core` acima do gate de 70% (números atuais no README).
- Carga: ~236 e ~905 req/s sem erro de negócio; gargalo real (pool do banco) achado e corrigido; consistência `payment_order` × `ledger_reservation` verificada em SQL (0 divergências).
- Caos em dev (`scripts/chaos-test.py`): core, Kafka, Debezium e Postgres fora ⇒ recuperação **sozinha**, sem reiniciar o app; banco fora devolve **503**, não 500.
- Segurança: Trivy (fs + imagem) e Semgrep executados; 28 de 30 HIGH/CRITICAL corrigidos (`docs/security-scan.md`).
- Rotação da chave AES com re-criptografia em lote (`PiiReencryptionIT`).
- QR dinâmico, Pix Agendado, MED e Pix Automático ponta a ponta no ambiente local (mocks), com ITs (`HttpDynamicQrPayloadAdapterTest`, `ScheduledPaymentApiIT`, `MedCaseApiIT`, `AutomaticPixApiIT`) e `scripts/demo-flow.py`.
- Webhook de Pix recebido ponta a ponta: cadastro BCB, entrega assinada ao receptor HTTPS, retry/DEAD/reenvio, anti-SSRF (`WebhookApiIT`, testes de política/sender/serviço, roteiro `demo-flow.py`).
- Prova de posse por OTP de ponta a ponta (`OwnershipProofServiceTest`, `PixKeyOwnershipProofIT`, contrato do gateway).
- Actuator fora da porta pública (verificado com o perfil `prd`), alertas testados com `promtool`, `outbox_event` com limpeza.

Em resumo: o serviço está funcional ponta a ponta **com mocks** e foi submetido a carga, caos e varreduras; a ida para produção depende de confirmar os gateways reais, popular contas, fechar o IdP (`aud`/`iss`/`pix_accounts`), decidir o escopo funcional que falta e obter o parecer do compliance.
