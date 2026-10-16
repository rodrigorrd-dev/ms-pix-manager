# RFC-001 — ms-pix-manager: Avaliação e Plano de Implementação

> Levantamento feito em **2026-10-15**. Documento vivo (template da skill `rfc-writing`): marque `- [ ]` conforme os itens forem entregues e registre no §8.
> Vocabulário RFC 2119: **MUST** / **SHOULD** / **MAY**.
> Stack: **Java 21 · Spring Boot 3.x · PostgreSQL · Liquibase · Kafka (Avro + Schema Registry + Kafka UI) · Debezium Outbox · Springwolf · Testcontainers**.

## 1. Objetivo

Construir o `ms-pix-manager`, microsserviço de um **PSP direto (ISPB próprio)** que gerencia o ciclo de vida Pix — **recebedor** (cobranças imediatas e com vencimento, locations, Pix recebidos, devoluções, webhooks), **pagador** (ordens por chave/dados manuais/BR Code, **Pix Agendado único e recorrente**, **Pix Automático lado pagador**, limites), **DICT** (chaves, reivindicações) e **MED 2.0** (infração, bloqueio, **rastreamento em camadas**, devolução, **contestação em 80 dias**) — sem falar diretamente com o SPI (via `spi-gateway`) nem com o DICT (via `dict-gateway`), com saldo no **core bancário** por API idempotente (reservar → confirmar|cancelar por `endToEndId`) — aderente à **API Pix 2.10.0** e à **API DICT 2.12.1** do BCB, usando **CPF e CNPJ (numérico e alfanumérico)** validados por dígito verificador, com consistência transacional (Outbox), mensageria Avro idempotente e rastreabilidade completa.

**Por que agora:** não existe código nem esquema (workspace vazio). Escrever a spec antes evita os defeitos recorrentes que as skills registram: dual-write sem outbox, idempotência registrada antes do efeito, retry de erro de negócio, propriedade de config declarada e ignorada, contrato de API com `DELETE` + body, listas sem paginação.

## 2. Estado atual (Baseline)

| Item | Hoje |
|---|---|
| Código/repositório | nenhum (`/workspace/pix` vazio, sem git) |
| Banco | nenhum |
| Documentação BCB | API Pix 2.10.0 e DICT 2.12.1 lidas em 2026-10-15 (`09`) |
| Skills aplicáveis | 30 cadastradas em `~/.claude/skills`; 21 usadas (tabela §6.2) |
| Decisões já tomadas pelo solicitante | Hexagonal **por módulo de negócio**, PostgreSQL, Liquibase, Kafka + Schema Registry + Kafka UI, producer idempotente Avro, consumer com retry/DLT e inbox, Outbox via Debezium, tracing + métricas de lag, Springwolf, Testcontainers, escopo **completo**, **Resource Server** (Keycloak só dev/teste), OTel→Prometheus/Tempo/Loki/Grafana, GitHub Actions + Trivy, Docker Compose em VPS (piloto), sem Redis na v1 — log completo em `10` |
| Decisões pendentes | `10-decisions-and-open-questions.md` §3 (E1–E5, F1–F3) |

## 3. Forma alvo (Target Shape)

```
                       ┌────────────────────────── ms-pix-manager (1 deployable, hexagonal) ───────────────────────────┐
 Clientes (PSP/Empresa)│  api/rest  ── UseCases ──► core/application (services puros, sem Spring)                        │
  OAuth2+JWT  ────────►│  /api/pix/v2 (compat BCB)                │ ports out                                            │
                       │  /v1/… (gestão)                          ▼                                                      │
                       │  docker/messaging (consumers, inbox, DLT) PersistencePortOut → PostgreSQL (+ outbox, inbox)      │
 spi-gateway ◄──Kafka──┤  docker/dict (HTTP+assinatura XML)        LedgerPortOut   → core bancário (HTTP, idempotente)    │
 DICT BCB   ◄──HTTPS───┤  docker/webhook (dispatcher)              NotificationPortOut / WebhookPortOut                    │
                       └─────────────────────────────────────────────────────────────────────────────────────────────────┘
 PostgreSQL ──(WAL)──► Debezium (Outbox Event Router) ──► Kafka (Avro, Schema Registry) ──► consumidores + Kafka UI
```

Pronto significa: (a) todos os endpoints do §4.3 de `05` documentados e testados; (b) todas as tabelas de `04` criadas por Liquibase e validadas por `ddl-auto=validate`; (c) nenhum evento publicado sem passar pela outbox; (d) nenhum consumer processa a mesma mensagem duas vezes (inbox); (e) `mvn verify` verde com pirâmide de `08`; (f) dashboards/alertas de `07` provisionados.

## 4. Gap Analysis (por área, com checklist)

### 4.1 Domínio
- [ ] VOs: `Cpf`, `Cnpj`, `Money`, `TxId`, `EndToEndId`, `PixKeyValue`, `Ispb` — todos auto-validáveis (`code-design`).
- [ ] Agregados: `PixKey`, `KeyClaim`, `Charge` (imediata/vencimento), `ReceivedPix`, `Refund`, `PaymentOrder`, `MedCase`, `WebhookSubscription`.
- [ ] Máquinas de estado com `canTransitionTo` (cobrança, devolução, ordem, claim, MED, webhook delivery).
- [ ] Resultado selado (`sealed interface …Result`) para falhas esperadas (limite excedido, chave inexistente) em vez de exceção.
### 4.2 Persistência
- [ ] 37 tabelas + 1 MV em `04` via Liquibase (SQL puro + `--rollback`) — **já validado** em Postgres 16.
- [ ] `@Version` nos agregados com escrita concorrente; `If-Match`/ETag **com 428** quando ausente.
- [ ] CPF criptografado (AES-GCM) + índice cego (HMAC) — falha **fail-fast** se a chave faltar (lição do converter com fallback silencioso).
- [ ] Índices para cada `WHERE`/`ORDER BY` real (checklist `jpa-patterns`).
### 4.3 API REST
- [ ] Agendado (`/v1/scheduled-payments`), Pix Automático (`/v1/automatic-authorizations`), contestação MED, reenvio de webhook, escopo `pii.document.read`.
- [ ] Compat BCB: `/api/pix/v2/{cob,cobv,loc,pix,webhook}` (nomes e payloads literais do OpenAPI 2.10.0).
- [ ] Gestão: `/v1/pix-keys`, `/v1/key-claims`, `/v1/payment-orders`, `/v1/med-cases`, `/v1/limits` (plural, kebab-case).
- [ ] Erros RFC 9457 com catálogo único `PIX-xxx`; 405/415/429 com handler próprio (não cair no 500).
- [ ] Idempotency-Key persistente com hash do corpo e replay da resposta original.
### 4.4 Mensageria
- [ ] 6 tópicos de saída (outbox) + 4 de entrada (`06`), Avro `BACKWARD_TRANSITIVE`.
- [ ] Outbox + Debezium EventRouter; producer idempotente (`enable.idempotence=true`, `acks=all`) para DLT/produtores diretos.
- [ ] Consumers: retry com backoff exponencial + jitter, DLT por tópico, **inbox** transacional.
- [ ] Springwolf AsyncAPI em `/springwolf/docs` e UI.
### 4.5 Integrações
- [ ] `DictGatewayClient` (HTTP; 4xx→negócio, 429/5xx→técnico; **sem** mTLS/XMLDSig/rate limit no ms — ficam no dict-gateway).
- [ ] `CoreLedgerClient` (`reservar`/`confirmar`/`cancelar` idempotentes por `endToEndId`) com timeouts separados e circuit breaker.
- [ ] `WebhookDispatcher` com HMAC/mTLS e tentativas persistidas.
### 4.6 Observabilidade e segurança
- [ ] Tracing OTel (HTTP → DB → Kafka propagando `traceparent` pela outbox), métricas de **lag**, logs JSON com MDC (`correlationId`, `e2eId`, `txid`, sem PII).
- [ ] mTLS no gateway, scopes BCB, `@PreAuthorize` em 100% dos endpoints (teste de reflexão).
### 4.7 Qualidade
- [ ] Pirâmide completa `08`; ArchUnit com 7 regras; Gatling com gate p95; JaCoCo com threshold real.

## 5. Ordem de implementação (prioridade, base primeiro)

1. **Fundação:** projeto Maven, Liquibase 001–003 (extensões, domínios, tabelas base), docker-compose, Testcontainers base, ArchUnit, CI (`§4.7`).
2. **Domínio compartilhado:** `Cpf`/`Cnpj`/`Money`/`TxId`…, catálogo `ErrorCodes`, `Clock` injetável, `TransactionRunner`.
3. **Outbox + inbox + idempotência** (infra transversal) e Debezium; publicar um evento fim a fim antes de qualquer feature.
4. **Recebedor:** loc → cob → cobv → Pix recebido (via consumer SPI) → devolução → webhook (+ reenvio manual).
5. **DICT:** chaves → reivindicação → sincronização/reconciliação (CID).
6. **Pagador:** limites (noturno/dispositivo/carência) → ordem de pagamento → BR Code → status SPI → reservar/confirmar/cancelar.
7. **Agendado** → **Pix Automático (pagador)** (reusam o caminho de `pagamento`).
8. **MED 2.0:** infração → bloqueio → camadas (`med_hop`) → devolução → contestação 80 d.
9. **Hardening:** resiliência, carga (Gatling), segurança, runbooks, dashboards.

## 6. Convenções adotadas

### 6.1 Código e nomenclatura (`architecture-conventions`, `clean-code`, `code-design`, `solid-principles`)
- **Toda a nomenclatura em inglês** (módulos, pacotes, classes, métodos, tabelas, colunas, tópicos, métricas, IDs `FR/BR/NFR`, nomes de arquivo); **termos regulatórios literais** (`cpf`, `cnpj`, `txid`, `endToEndId`, `ispb`, `MED`, `DICT`, `SPI`, `pacs.*`, enums e campos do BCB). Prosa em pt-BR. Detalhes e mapa pt→en em [`11-naming-glossary.md`](11-naming-glossary.md).
- Sufixos: `UseCase` (porta de entrada), `Service` (sem `Impl`, sem `@Service`; beans em `UseCaseConfig`), `PortOut`, `Adapter`, `Entity`, `EntityMapper` (MapStruct), `Request/Response`, `Event`, `Listener`, `Consumer`, `Policy`, `Guard`, `…Result` (sealed).
- Domínio **sem** Spring/JPA/Kafka/Jackson/Lombok (ArchUnit). Entidades JPA separadas do domínio.
- Dinheiro `BigDecimal`/`numeric(15,2)`; datas `Instant`/`timestamptz`; **nunca** `double`, `float`, `LocalDateTime`.
- Códigos de erro **somente** via `ErrorCodes` (`PIX-4xx/5xx`); literal de código fora do catálogo falha no ArchUnit/grep de CI.
- `Optional` só em retorno; sem `null` em coleção; ids e tempo vêm de gerador/`Clock` injetados.

### 6.2 Skills aplicadas
| Skill | Onde foi aplicada |
|---|---|
| `rfc-writing` | este documento (9 seções) |
| `architecture-conventions` | §6.1, `03` (pacotes, sufixos, glossário) |
| `architecture-review` | `03` regras de dependência + checklist |
| `c4-model` | `03` (Contexto, Container, Componente, Dinâmico, Deployment em Mermaid) |
| `solid-principles`, `design-patterns`, `clean-code`, `code-design`, `safe-refactoring` | `03` §6, `02` (regras como tipos), convenções |
| `spring-boot-patterns` | `03` (wiring, `UseCaseConfig`, exceções, config tipada) |
| `jpa-patterns` | `04` (tipos, `@Version`, IDENTITY vs SEQUENCE, índices, projeções, sem N+1) |
| `cqrs-architecture` | `03` §7 (CQRS-lite: `*QueryPortOut`; view materializada de conciliação) |
| `api-contract-review` | `05` (verbos, versão, 428, Idempotency-Key, paginação, Problem Details) |
| `resilience-patterns` | `07` (timeouts, retry+jitter, circuit breaker, outbox, técnico × negócio) |
| `cache-patterns` | `07` (cache-aside via porta, invalidação por evento, degradar para miss) |
| `concurrency-review` | `07`, `08` (locks, `@Version`, virtual threads, scheduler com ShedLock) |
| `security-audit` | `07` (OWASP, PII, chaves, mTLS, headers) |
| `logging-patterns` | `07` (JSON, MDC, mascaramento, auditoria) |
| `test-pyramid`, `test-quality`, `performance-testing`, `performance-smell-detection` | `08` |
| `java-migration`, `maven-dependency-audit` | `03` §9 (Java 21 → 25 pronto, BOM, auditoria no CI) |
| `java-code-review`, `git-commit`, `changelog-generator`, `issue-triage` | fluxo de PR/commit/release em `README.md` |

### 6.3 Processo
- Spec-Driven: **nenhuma feature** sem seção correspondente em `02` (FR/BR com ID) + cenário Gherkin + linha na matriz de rastreabilidade (`02` §8).
- Mudança de comportamento ⇒ atualizar spec **no mesmo commit** (lição de spec obsoleta de `rfc-writing`).
- Commits Conventional Commits; PR com checklist `java-code-review`.

## 7. Definition of Done (por área)

| Área | Critério verificável |
|---|---|
| Domínio | `mvn test` roda sem Spring no classpath do `core`; ArchUnit verde; 100% dos VOs com teste de limites (CPF válido/ inválido/ todos-iguais/ máscara) |
| Persistência | `*IT` com Testcontainers PostgreSQL executa o Liquibase real; `ddl-auto=validate`; teste de concorrência prova 1 vencedor com `@Version` |
| API | Cada endpoint tem `@WebMvcTest` (200/4xx/401/403/428); contrato OpenAPI do BCB comparado por teste; `grep` de `@PreAuthorize` = 100% |
| Outbox/Debezium | IT sobe Postgres + Kafka + Schema Registry + Kafka Connect/Debezium (Testcontainers) e consome o evento Avro com o schema registrado |
| Consumer | IT prova: mensagem duplicada não duplica efeito (inbox); falha técnica retenta N vezes e vai à DLT; falha de negócio vai direto à DLT sem retry |
| Idempotência HTTP | Mesma chave+corpo → mesma resposta (replay); mesma chave+corpo diferente → 422; falha primeira tentativa **não** queima a chave |
| Observabilidade | Trace único cobre HTTP → DB → outbox → Kafka → consumer; métrica `kafka_consumer_lag` com alerta |
| Segurança | `dependency-check` sem CVSS ≥ 7; nenhum segredo em `application*.yml`; PII ausente de logs (teste) |
| Carga | Gatling: p95 criar cobrança ≤ 150 ms @ 300 rps; falha < 0,1% |

## 8. Progress Log

| Data | O que foi feito | Notas |
|---|---|---|
| 2026-10-15 | Spec inicial (docs 01–10), contratos OpenAPI/Avro, DDL Liquibase, docker-compose, connector Debezium | Baseado em API Pix 2.10.0 / DICT 2.12.1 |
| 2026-10-15 | **Rodada 1 de respostas** (A–D): dict-gateway/spi-gateway, ledger 2 fases por e2e, CNPJ alfanumérico (algoritmo verificado), limites (noturno/dispositivo/carência), **Pix Agendado e Pix Automático na v1**, **MED 2.0 em camadas + contestação 80 d**, módulos por negócio, Resource Server+Keycloak, Loki, GitHub Actions/Trivy | DDL: 29→**37 tabelas** (+`trusted_device`, `med_hop`, `med_contestation`, `scheduled_payment`, `scheduled_payment_occurrence`, `pix_automatic_*`); revalidado em Postgres 16; Avro +3 schemas; riscos F1/F2 e pendências E1–E5 em `10` |

## 9. Backlog remanescente (deixado de fora **de propósito**)

| Item | Por quê |
|---|---|
| Lado **recebedor** do Pix Automático (`rec`, `solicrec`, `cobr` como emissor), `locrec`, `lotecobv` | v1 cobre o Pix Automático **só do lado pagador** (B3) |
| Pix Saque/Troco | Exige agentes (`modalidadeAgente`); colunas previstas, regras não implementadas |
| Multi-tenant (vários ISPB) | A3: ISPB já parametrizado e no evento; falta isolamento de dados |
| Redis | C5 — só depois, como cache de DICT |
| Kubernetes/Helm/IaC | C4 — opcional; piloto em Docker Compose/VPS |
| Crypto-shredding | B7 — só após o prazo legal |
