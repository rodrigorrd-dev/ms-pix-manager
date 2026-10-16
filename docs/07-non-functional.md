# 07 — Requisitos não funcionais: resiliência, segurança, observabilidade, performance

> Skills: `resilience-patterns`, `security-audit`, `logging-patterns`, `cache-patterns`, `concurrency-review`, `performance-smell-detection`, `performance-testing`.

## 1. SLOs (Pix é 24x7x365)

> **Dois perfis de infraestrutura (decisão C4 + risco F2 em `10`):** (1) **alvo de produção com HA** — SLOs abaixo; (2) **VPS com Docker Compose (single-node)** — adequado a **piloto/homologação**: um host único não sustenta 99,95% (≈22 min/mês); SLO realista **99,5%** (≈3,6 h/mês), RPO ≤ 5 min (backup/WAL archiving), RTO ≤ 60 min (restore + compose up). Participante direto com ISPB próprio exige continuidade/contingência: **produção deve ter ≥ 2 nós de aplicação, PostgreSQL com réplica sincronizada/PITR e Kafka com 3 brokers**.

| SLI | SLO | Janela | Alerta |
|---|---|---|---|
| Disponibilidade `/api/pix/v2/*` e `/v1/*` (não-5xx) | 99,95% | 30 d | burn rate 2× em 1 h |
| Latência p95 `PUT /cob/{txid}` | ≤ 150 ms | 5 min | > 150 ms por 10 min |
| Latência p95 `POST /v1/payment-orders` até `SENT_TO_SPI` | ≤ 3 s | 5 min | > 3 s por 5 min |
| Processamento de Pix recebido (SPI → `pix.received.v1`) | p95 ≤ 2 s | 5 min | > 5 s |
| Lag de consumer | < 1.000 msgs e < 30 s | contínuo | 5 min |
| Entrega de webhook | 99% em ≤ 60 s | 1 h | — |
| RPO / RTO | ≤ 1 min / ≤ 15 min | — | teste de restore trimestral |

## 2. Resiliência (resilience-patterns)

### 2.1 Princípio: **técnico × negócio**, decidido no ponto da chamada
| | Falha técnica | Erro de negócio |
|---|---|---|
| Exemplos | timeout, connection refused, 5xx, 429, `CallNotPermitted` | CPF inválido, chave inexistente (404 DICT), saldo, limite, 4xx definitivo |
| Ação | retry c/ backoff+jitter; depois erro 502/503 ou `FAILED_TECHNICAL` | **nunca** retry; `422`/`REJECTED` |
| Onde | no `*Adapter` (traduz exceção do cliente) | no mesmo adapter, **4xx → `BusinessException` com código**, 5xx/timeout → `IntegrationException` |
`BusinessException` e `IntegrationException` são **irmãs**. O circuit breaker ignora `BusinessException`. Teste de contrato cobre **404, 422 e 500** separadamente (lição: 4xx não pode ser retentado como 5xx).

### 2.2 Timeouts (explícitos em todo cliente HTTP — connect e read separados)
| Dependência | connect | read | total | Observação |
|---|---|---|---|---|
| `dict-gateway` (consulta) | 500 ms | 2 s | 3 s | gateway aplica cache/limite/retentativas; o ms **não** repete retentativas já feitas pelo gateway |
| `dict-gateway` (escrita) | 500 ms | 5 s | 6 s | idempotente por `X-Request-Id` |
| Core/ledger (`reservar`/`confirmar`/`cancelar`) | 500 ms | 2 s | 3 s | idempotente por **`endToEndId`** |
| Webhook do recebedor | 1 s | 5 s | 6 s | |
| PostgreSQL | `connectionTimeout` Hikari **3 s** (não 30 s), `statement_timeout` 5 s, `lock_timeout` 2 s | | | pool pequeno falha rápido |
| Kafka | `request.timeout.ms=30s`, `delivery.timeout.ms=120s` | | | |
Pior caso somado de um caso de uso síncrono (`POST /payment-orders`: DICT 3 s + ledger 3 s) deve caber no SLO ⇒ chamadas em **paralelo** quando independentes (virtual threads/`StructuredTaskScope` se estável; senão `CompletableFuture` com timeout).

### 2.3 Retry
- `Resilience4j Retry` **apenas** em adaptadores de saída, `maxAttempts=3`, backoff exponencial `200 ms × 2^n` **com jitter ±50%**, só `IntegrationException` retryável (timeout/5xx/429 respeitando `Retry-After`). Escritas só com chave de idempotência.
- **Uma camada de retry por chamada**: se o consumer usa retry-topic, o adapter chamado dentro dele **não** retenta (senão atempts multiplicam).
- Limites vêm de `@ConfigurationProperties` e há teste que garante que **toda propriedade declarada é lida**.

### 2.4 Circuit breaker + bulkhead + rate limit
| Dependência | CB (janela/limiar/espera) | Bulkhead | Fallback |
|---|---|---|---|
| `dict-gateway` consulta | 50 chamadas / 50% falha / 30 s | semáforo 50 | `503 PIX-503-001` + `Retry-After` |
| `dict-gateway` escrita | 20 / 50% / 60 s | 20 | chave fica `PENDING_DICT` e o sweeper retenta |
| Core/ledger | 50 / 40% / 30 s | 100 | ordem `FAILED_TECHNICAL` e `cancelar` reserva; sem fallback otimista (dinheiro) |
| Webhook | por assinante (circuito por URL) | 200 global | fila `webhook_delivery` com backoff |
- **Limite de consultas ao DICT, cache e retentativas ficam no `dict-gateway`** (decisão A1): o ms **não** implementa token bucket nem retry de DICT; trata `429` (respeita `Retry-After`) e `5xx` do gateway como **falha técnica** sem abrir o CB por `429`.
- Bulkhead por dependência para uma lentidão **não** consumir as threads/conexões das demais.

### 2.5 Consistência entre sistemas (sem dual-write)
| Cenário | Solução |
|---|---|
| Gravar agregado + publicar evento | **Outbox** na mesma transação (`06`) |
| Gravar + chamar core | `ledger_reservation` (`PENDING`) na transação → worker chama **`reservar`** com **`endToEndId`** como chave de idempotência → `RESERVED`; liquidação ⇒ **`confirmar`** (`CONFIRMED`); rejeição/expiração/falha definitiva ⇒ **`cancelar`** (`CANCELLED`). Repetir qualquer chamada com o mesmo e2e é seguro |
| Gravar chave + chamar DICT | estado `PENDING_DICT` + sweeper; resposta ao cliente `202`/`201` com status |
| Efeito colateral "fire-and-forget" (webhook) | assíncrono por consumer da outbox; nunca na thread que já fez commit |
| Chave de idempotência | só marcada `COMPLETED` **após** o efeito; falha técnica não a queima |
**Sweepers** (ShedLock/`SKIP LOCKED`): `PENDING` de ledger > 30 s; ocorrências de agendamento vencidas sem execução; cobranças/tentativas de Pix Automático `SOLICITADA` além do prazo; contestações MED vencidas; `RESERVED` sem `SENT_TO_SPI` > 60 s; `SENT_TO_SPI` sem pacs.002 > 5 min (consulta ao spi-gateway); chave `PENDING_DICT` > 2 min; refund `EM_PROCESSAMENTO` > 10 min. Cada sweeper tem métrica `pix_sweeper_repaired_total{kind}` e alerta se > 0 de forma contínua (indica bug).

### 2.6 Degradação
- Sem Redis (C5) ⇒ nada a degradar; cache de DICT está no **dict-gateway**. Cache local (Caffeine, TTL 5 min) só para dados imutáveis (participantes); falha de cache **vira miss** (cache-patterns).
- dict-gateway fora ⇒ leitura de cobranças/Pix continua; criação de **cobrança** usa chave local `ACTIVE` (não precisa do DICT); pagamento **por chave** falha com 503; pagamento **manual** segue.
- Kafka/Connect fora ⇒ API continua gravando na outbox; eventos atrasam (alerta no slot lag).

## 3. Segurança (security-audit)

### 3.1 Autenticação/autorização
- **Somente Resource Server** (C2): valida JWT por **JWKS** (cache com refresh), `iss`, `aud`, `exp`/`nbf` (clock skew ≤ 60 s) e algoritmo permitido (RS256/ES256; **rejeita `alg=none`/HS***); `client_credentials` emitido por IdP externo — o ms **não emite token nem cadastra cliente**. Escopos BCB (`cob.*`, `cobv.*`, `pix.*`, `webhook.*`, `payloadlocation.*`) e de gestão (`pixkey.*`, `payment.*`, `limit.*`, `med.*`, `schedule.*`, `automatic.*`, `report.read`, **`pii.document.read`**) → `GrantedAuthority`.
- **mTLS terminado no gateway**; o ms confia somente no gateway (rede privada/NetworkPolicy) e **não** aceita identidade vinda de header sem validar JWT. mTLS/XMLDSig do DICT e do SPI são responsabilidade dos **gateways**.
- Dev/teste: **Keycloak** em container (compose) ou **Testcontainers** (`08`).
- `@PreAuthorize` em **100%** dos métodos de controller (teste de reflexão); teste que valida o **literal** do escopo contra o catálogo (typos viram erro de build). Sem `role` desconhecido ⇒ perfil **mínimo** (nunca privilegiado).
- **Posse (ownership)** checada no *service* (`OwnershipGuard`): recurso fora do escopo do cliente ⇒ **404**. Vale para **todo** agregado, não só um.

### 3.2 Proteção de dados (LGPD)
| Controle | Detalhe |
|---|---|
| CPF/CNPJ/valor de chave (CNPJ numérico e alfanumérico) | AES-256-GCM + HMAC (chaves distintas, KMS/Vault, rotação por `key_version`) — **fail-fast** sem chave; `AttributeConverter` nunca cai para texto puro |
| Máscara | `***.982.247-**` em respostas, logs, eventos, traces |
| `toString()` | records com PII sobrescrevem `toString` (mascarado); nunca logar request/response inteiro |
| Cache/eventos/DLT | sem PII em claro (exceto o tópico de **entrada** do spi-gateway, ACL restrita, retenção 7 d) |
| Retenção | ≥ 5 anos (B7); exclusão LGPD **não** prevalece sobre retenção legal; *crypto-shredding* no backlog e **só após o prazo legal** |
| Documento completo | só com `pii.document.read`; **todo acesso grava `audit_log`** (quem, cliente, quando, recurso) |
| Segredos | nada em `application*.yml`; `prd` **não sobe** sem `DB_PASSWORD`, chaves de cripto, truststore; perfil `local` não ativável em `prd` |

### 3.3 Entrada e injeção
Bean Validation por allowlist (enums, regex `^[a-zA-Z0-9]{26,35}$`, `@Digits`, `@Size`, `@Max`); VOs revalidam no core. SQL só com parâmetros (JPA/`@Query` com `:param`; `grep nativeQuery` revisado). Sem desserialização polimórfica (`@JsonTypeInfo` proibido; Avro `SpecificRecord`). Upload inexistente.

### 3.4 Exposição
Actuator só `health,info,prometheus` em porta de gestão separada; Swagger/Springwolf/Kafka UI **fechados em `prd`**; headers (`HSTS`, `X-Content-Type-Options`, `Cache-Control: no-store`, CSP para UI se houver); CORS definido (gateway ou serviço, **um** dono); erros sem stack/SQL.

### 3.5 Assinatura e webhook
Assinatura XML/mTLS do DICT e do SPI ficam nos **gateways** (A1). Webhook: HTTPS obrigatório, assinatura HMAC `x-pix-signature` (+ timestamp para anti-replay ≤ 5 min), mTLS opcional, `SSRF` bloqueado (nega IP privado/link-local/metadata na validação de URL e no connect).

### 3.6 Supply chain
**GitHub Actions** (C4): `mvn verify` (testes + JaCoCo com threshold) → **OWASP dependency-check** (falha CVSS ≥ 7) → build da imagem → **Trivy** (falha em HIGH/CRITICAL com fix) → push por digest; SBOM (CycloneDX); `gitleaks`; `allowed-licenses`. Deploy: **Docker Compose na VPS** (piloto/hml); manifestos Kubernetes/Helm opcionais no repositório.

## 4. Observabilidade

### 4.1 Tracing (OpenTelemetry via Micrometer Tracing)
- Propagação W3C `traceparent`: HTTP in → JDBC → **`outbox_event.trace_parent`** → header Kafka (Debezium) → consumer → HTTP out (DICT/ledger/webhook).
- Spans de negócio nomeados (`pix.charge.create`, `pix.received.apply`), atributos **não-PII**: `pix.e2e_id` (opcional, é identificador de transação), `pix.txid`, `pix.charge.status`.
- Amostragem: 100% de erros + 10% (parent-based) em `prd`; `traceId` em todo log e em todo Problem Details.
- Export OTLP → Collector (`ops/otel`) → **Tempo** (traces), **Prometheus** (métricas), **Loki** (logs), visualizados no Grafana; processor remove atributos sensíveis.

### 4.2 Métricas (Micrometer → Prometheus)
| Grupo | Métricas |
|---|---|
| Negócio | `pix_charges_created_total{type}`, `pix_received_total{reconciliation}`, `pix_refunds_total{status}`, `pix_payment_orders_total{status}`, `pix_med_cases_open`, `pix_limit_rejections_total{scope}` |
| Mensageria (**lag**) | `kafka_consumer_fetch_manager_records_lag_max`, `kafka_consumergroup_lag` (exporter), `pix_dlt_messages_total`, `inbox_duplicates_total`, `pg_replication_slot_lag_bytes`, `debezium_metrics_MilliSecondsBehindSource` |
| Resiliência | `resilience4j_circuitbreaker_state`, `…_calls_seconds`, `pix_dict_rate_limited_total`, `pix_sweeper_repaired_total{kind}` |
| Plataforma | `hikaricp_connections_*`, `jvm_*` (incl. threads virtuais, pinning `jdk.VirtualThreadPinned` via JFR), `http_server_requests_seconds` com `percentilesHistogram` |
Cardinalidade: **proibido** `txid`, `e2eId`, CPF ou `clientId` livre como label; usar exemplars/traces para isso.

### 4.3 Logs (logging-patterns)
- JSON (Logstash encoder) → coletor OTel/Promtail → **Loki** (labels de baixa cardinalidade: `app`, `env`, `level`; `traceId` como campo, não label) com MDC: `traceId`, `spanId`, `correlationId`, `clientId`, `aggregateType`, `aggregateId`, `e2eId`, `txid` (identificadores de transação **são** aceitos), **sem** CPF/nome/valor de chave.
- Níveis: `INFO` transições de estado e eventos de negócio; `WARN` falha técnica com retry restante; `ERROR` só para o que exige ação (inclui stack **só** no log); nada de `catch` vazio; exceção logada **uma vez** na borda.
- Máscara em **todos** os appenders (conversor de mensagem + teste que injeta CPF e prova ausência no output).
- Auditoria funcional em `audit_log` (append-only) para back-office/MED/limites.
- MDC/`SecurityContext` propagados em tarefas assíncronas; limpos em `finally` em pools.

### 4.4 Health
`/actuator/health/liveness` (JVM) e `/readiness` (DB, Kafka producer metadata, Schema Registry **não** derruba readiness; DICT **não** entra em readiness — dependência externa não derruba o pod). Indicadores custom: `outboxLag`, `dictCircuit`.

## 5. Concorrência (concurrency-review)
- Aggregates: `@Version`; fluxos de saldo/limite/devolução com **UPDATE condicional atômico** (`04` §3.5), não `read→decide→write`.
- Locks: nenhum I/O lento segurando lock; `FOR UPDATE SKIP LOCKED` nos workers; ordem de lock documentada quando houver dois (`pix_received` antes de `pix_refund`).
- Jobs: ShedLock com `lockAtMostFor` > pior caso e `lockAtLeastFor` curto; um job que estoura renova (ou falha rápido).
- Virtual threads: habilitadas **após** teste de carga; Hikari dimensionado (`maximumPoolSize` ≈ 4 × vCPU, ≤ conexões PG disponíveis ÷ réplicas); evitar `synchronized` ao redor de I/O (pinning); `ThreadLocal` mínimo, preferir `ScopedValue` quando estável.
- Teste: `*ConcurrencyTest` (`@Tag("concurrency")`, Testcontainers, `CyclicBarrier`) — 1 vencedor em `@Version`, Σ devoluções ≤ valor com N threads, consumo de limite atômico, `Idempotency-Key` simultânea ⇒ 1×201 e N−1×409/replay.

## 6. Performance (performance-smell-detection / testing)
- Regras: sem N+1 (contador de statements em IT), paginação sempre, projeções p/ listas, `Pattern` pré-compilado, sem `String.format` em hot path de log (usar `{}`), streams sem efeito colateral, `BigDecimal` com `MathContext`/escala fixa, JSON com Jackson módulo `afterburner` desnecessário em Java 21 (medir antes).
- Medir antes de otimizar: JFR + Gatling; `-Pperf` com `hibernate.SQL=WARN`.
- Cargas-alvo (Gatling `PixManagerSimulation`): 300 rps sustentado 15 min, pico 1.000 rps 2 min; cenários: `PUT /cob`, `GET /cob/{txid}`, consumo de `payment-settled` (produtor sintético 500 msg/s), corrida de idempotência; **asserts** globais: p95 < 150 ms, falhas < 0,1%, lag < 1.000 ao final.
- Capacidade: 3 réplicas 2 vCPU/2 GiB; PG 8 vCPU/32 GiB, `shared_buffers` 8 GiB; `max_connections` 200 (via PgBouncer em transaction mode **cuidado**: prepared statements e `LISTEN` — a app usa JDBC `prepareThreshold=0` se PgBouncer).
