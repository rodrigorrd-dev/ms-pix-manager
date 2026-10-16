# 08 — Estratégia de testes (pirâmide completa, Testcontainers)

> Skills: `test-pyramid`, `test-quality`, `performance-testing`, `concurrency-review`. Convenções: `*Test` = unitário/slice/contrato/arquitetura (Surefire, sem Docker); `*IT` = integração/e2e (Failsafe, Docker); `@Tag("concurrency")` = `mvn -Pconcurrency test`; Gatling em `src/gatling` (`-Pperf`).

## 1. Forma alvo

```
                ▲  E2E  (2–4 classes)  app real + Postgres + Kafka + Schema Registry + Debezium + WireMock(DICT/ledger)
               / \
              /IT \  @DataJpaTest/Liquibase real · Kafka consumers/producers · Outbox→Debezium→Kafka · @WebMvcTest(*WebTest)
             /-----\
            / Unit  \  MockitoExtension, sem Spring: domínio, VOs, services, policies, mappers
           /---------\            alvo por classes: ~70% unit / 25% integração / 5% e2e
  Eixos ortogonais: Arquitetura (ArchUnit) · Contrato (OpenAPI BCB, Avro, AsyncAPI, WireMock) · Concorrência · Performance (Gatling)
```

## 2. Infra de teste compartilhada (a primeira entrega)
| Classe | Papel |
|---|---|
| `AbstractPostgresIT` | Singleton `PostgreSQLContainer("postgres:16-alpine")` com `wal_level=logical`; `@DynamicPropertySource`; Liquibase **real**; `ddl-auto=validate` |
| `AbstractKafkaIT` | Singleton `ConfluentKafkaContainer` (cp-kafka 7.7.x, KRaft) + **Schema Registry** (`GenericContainer cp-schema-registry`, rede compartilhada) |
| `AbstractOutboxPipelineIT` | Adiciona `DebeziumContainer`/`GenericContainer quay.io/debezium/connect` ligado a Postgres+Kafka; registra `outbox-connector.json`; helper `awaitEvent(topic, eventId)` (Awaitility) |
| `WireMockExtension` | **dict-gateway**, **core/ledger** (reservar/confirmar/cancelar), webhook receivers; faults (`CONNECTION_RESET_BY_PEER`, delay) |
| `KeycloakContainer` (`dasniko/testcontainers-keycloak`) | IdP real nos `*IT` de segurança: emite JWT `client_credentials` com escopos (`cob.write`, `pii.document.read`…); o ms valida via JWKS (`iss`/`aud`/`exp`) |
| `TestData` / `*Builder` | Dados válidos (CPF `529.982.247-25`, `txid` 32 chars); relógio fixo `Clock.fixed` (**regra**: todo teste cujo resultado dependa da hora — limite noturno 20h–6h, janelas, expiração — fixa o relógio com `FixedClock`; nunca depende do relógio da máquina) |
| `DeterministicIdGenerator` | ids/UUIDv7 previsíveis |
Containers **singleton por JVM** (start no `static {}`) e reutilizados; `testcontainers.reuse.enable=true` só local.

## 3. Camadas

### 3.1 Unitário (`*Test`)
- Cada VO: `CpfTest` — parametrizado: válidos (`529.982.247-25`, `52998224725`, `11144477735`), inválidos (`111.111.111-11`, DV errado, tamanho 10/12, letras, `null`, vazio, espaços), máscara (`***.982.247-**`), `toString` mascarado, igualdade; `CnpjTest` — **numérico e alfanumérico**: válidos `12.ABC.345/01DE-35`, `12abc34501de35` (normaliza), `11.222.333/0001-81`; inválidos `12ABC34501DE36` (DV), `00000000000000`, `12ABC34501DE3A` (DV não numérico), tamanho ≠ 14; **propriedade**: gerar 10 mil CNPJs alfanuméricos aleatórios com o DV calculado e todos devem validar; `MoneyTest` (escala 2, arredondamento HALF_EVEN, negativo).
- Services com todas as portas mockadas e relógio fixo (`CreateChargeServiceTest`, `ReceivePixServiceTest`, `RequestRefundServiceTest`, `CreatePaymentOrderServiceTest`, …). Resultados selados ⇒ `assertThat(result).isInstanceOf(TxIdConflict.class)`.
- Máquinas de estado: tabela de transições válidas/ inválidas parametrizada (`canTransitionTo`).
- Convenção `shouldXWhenY`, AAA, AssertJ, sem lógica no teste, sem `Thread.sleep` (Awaitility), `@DisplayName("BR-REF-03 …")` ligado à matriz de rastreabilidade (`02` §8).

- Novas regras: `LimitPolicyTest` (noturno PF 20h–6h incl. fronteiras 19:59/20:00/05:59/06:00 em `America/Sao_Paulo`, PJ isenta, dispositivo não cadastrado R$200/R$1000, **menor limite prevalece**, aumento vigora em [24h,48h], redução imediata, teto do banco), `ScheduleRetryWindowTest` (18h–21h), `ScheduledRecurrenceTest` (calendário mensal fim de mês, DST inexistente no BR mas fuso fixo), `AutomaticAuthorizationStateTest`, `IdRecTest` (29 chars, `R`/`N`), `MedLayerTrackingTest` (bloqueio parcial, avanço de camada, Σ ≤ rastreado), `MedContestationDeadlineTest` (79/80/81 dias).

### 3.2 Fatia web (`*WebTest`, `@WebMvcTest`)
- Por controller: 2xx; 400 (validação, parse); 401; 403 (escopo ausente); 404 (outro cliente → escondido); 409/412/**428**; 415; 405; 429 → corpo `application/problem+json` com `code` do catálogo.
- `@MockitoBean(name="…")` quando o `@PreAuthorize` resolver bean por nome (gotcha documentado).
- **Teste de reflexão** `ControllerPermissionLiteralsTest`: todo método tem `@PreAuthorize` e todo literal de escopo existe no catálogo.
- Teste de autorização **com a política real** (não só mock) variando o claim `scope`; `JwtValidationIT` com **Keycloak (Testcontainers)**: token válido ⇒ 200; `aud` errado ⇒ 401; `iss` errado ⇒ 401; expirado ⇒ 401; `alg=none`/HS256 forjado ⇒ 401; sem escopo ⇒ 403; **CPF completo só com `pii.document.read`** (e `audit_log` gravado).
- `IdempotencyWebTest`: replay devolve a mesma resposta; corpo diferente ⇒ 422; falha técnica não queima a chave.
- Logs: teste que envia CPF e verifica que **nenhum** appender o contém.

### 3.3 Integração de persistência (`*IT`)
- `*RepositoryIT/*AdapterIT` sobre `AbstractPostgresIT` (nada de H2): unicidades (`txid` por documento do recebedor — **mesmo txid, documentos diferentes ⇒ ok**), `CHECK`s, índices parciais, `@Version` (`ConflictException`), **UPDATE condicional** de devolução e limite, `LiquibaseSchemaIT` (aplica → **rollback de cada changeset** → reaplica), gatilhos append-only (`UPDATE med_case_history` falha), `StatementCountIT` (sem N+1: ≤ N statements por caso de uso).
- `CryptoConverterIT`: CPF gravado **cifrado**; busca só via hash; ausência de chave ⇒ contexto **não sobe**.
- `ReconciliationViewIT`: `REFRESH MATERIALIZED VIEW CONCURRENTLY` funciona e respeita fuso `America/Sao_Paulo` na virada do dia.

### 3.4 Mensageria (`*IT`) — o núcleo da escolha arquitetural
| Teste | Prova |
|---|---|
| `OutboxAtomicityIT` | Falha forçada após `INSERT outbox_event` ⇒ **rollback** do agregado **e** do evento (nenhum "evento fantasma"); sucesso ⇒ ambos presentes |
| `OutboxToKafkaIT` (pipeline completo) | Gravar agregado → Debezium → mensagem em `pix.received.v1` com **schema registrado**, headers `eventType`/`traceparent`/`id` e `key=aggregate_id`; desserializa com `KafkaAvroDeserializer` |
| `SchemaCompatibilityIT` | Cada `.avsc` registra e passa `BACKWARD_TRANSITIVE` contra a versão de `main`; mudança incompatível **quebra** o teste |
| `IdempotentProducerIT` | `enable.idempotence=true` efetivo (config do producer) e sem duplicata em retry simulado (broker paused/resume via Toxiproxy opcional) |
| `ReceivePixConsumerIT` | (a) mensagem válida ⇒ `pix_received` + cobrança `CONCLUIDA` + outbox; (b) **mesma mensagem 2×** ⇒ 1 efeito (inbox); (c) falha técnica ⇒ passa por `-retry-0..2` e termina na **DLT** após N; (d) falha de **negócio** (e2eId inválido) ⇒ **DLT direta sem retry**; (e) payload não-Avro (poison pill) ⇒ DLT e consumidor segue vivo |
| `RetryBackoffTest` | Backoff exponencial c/ jitter dentro de [0,5×, 1,5×] do nominal |
| `DltReplayIT` | Replay preserva `eventId` ⇒ inbox decide |
| `ConsumerLagMetricsIT` | Publica N msgs, pausa o consumer, afirma `kafka_consumer_fetch_manager_records_lag_max`/`kafka_consumergroup_lag` > 0 e volta a 0 |
| `TracePropagationIT` | Um `traceId` atravessa HTTP → outbox → Kafka (header) → consumer (verificado nos spans exportados por `InMemorySpanExporter`) |
| `AsyncApiContractTest` | `/springwolf/docs` igual a `docs/api/asyncapi/asyncapi.json` |
Nota de custo: `OutboxToKafkaIT` é o mais lento; roda em `mvn verify` e em job noturno completo; `OutboxAtomicityIT` e o resto rodam sempre.

### 3.5 Contrato (`*ContractTest`)
- **dict-gateway / core-ledger / webhook com WireMock**: 200, **404 → `BusinessException`**, **422 → `BusinessException`**, **429 (Retry-After) → técnica sem abrir CB**, 500 e **timeout → `IntegrationException`**; assert do **estado resultante** (ex.: chave volta a `PENDING_DICT`, reserva liberada), não só o tipo da exceção.
- Teste constrói o cliente **de produção** (mesma config de timeout/mTLS/assinatura), não um próprio.
- **OpenAPI BCB**: `BcbContractTest` valida request/response da superfície `/api/pix/v2` contra `docs/api/bcb/api-pix-openapi-2.10.0.yaml` (swagger-request-validator) e **falha se `info.version` mudar** sem atualização consciente; regressão das **divergências do BCB** (`09` §5).
- **Gestão**: `OpenApiContractTest` compara `/v3/api-docs` com `docs/api/ms-pix-manager.openapi.yaml`.
- `EnumWireValuesSnapshotTest`: valores literais dos enums (wire) não mudam.

### 3.6 Arquitetura (ArchUnit — `HexagonalArchitectureTest`)
Regras A1–A8 de `03` §2.1 (+ sem ciclos, + `*Service` sem `@Service/@Transactional`, + proibido `System.out`/`printStackTrace`, + `"PIX-` literal fora de `ErrorCodes`, + `core` sem Lombok/Jackson).

### 3.6b Novos fluxos da v1 (IT)
| Teste | Prova |
|---|---|
| `ScheduledPaymentExecutionIT` | Data chega ⇒ **1** `payment_order` por ocorrência (idempotente); saldo insuficiente às 06:00 ⇒ `RETRY_PENDING` com `next_attempt_at` em [18h,21h], evento de aviso; retry bem-sucedido ⇒ `EXECUTED` |
| `ScheduledRecurrenceIT` | Recorrente mensal gera sequência correta; falha de uma ocorrência não cancela as seguintes |
| `AutomaticAuthorizationConsumerIT` | `SpiAutomaticRequest` ⇒ autorização `RECEBIDA`; aceite ⇒ `APROVADA` + evento; rejeição `AP13` ⇒ `REJEITADA` |
| `AutomaticChargeExecutionIT` | Cobrança com autorização `APROVADA` ⇒ tentativa `AGND` ⇒ `payment_order origin=AUTOMATIC` com e2e próprio; **sem** autorização aprovada ⇒ `PIX-422-060`; `allow_retry=false` ⇒ sem `NTAG` |
| `LedgerTwoPhaseIT` | `reservar` repetido com o mesmo e2e ⇒ 1 reserva; liquidação ⇒ `confirmar`; rejeição ⇒ `cancelar`; falha técnica entre as fases é reparada pelo sweeper |
| `MedLayerTrackingIT` | Caso de R$500 com saldo R$120 ⇒ bloqueia 120, abre camada 2 (parent) com R$380; novo crédito de R$200 na camada 2 ⇒ bloqueia 200; Σ nunca excede o rastreado; respeita janela de 90 dias |
| `MedContestationIT` | Contestação aos 79 dias OK, aos 81 ⇒ `PIX-422-070`; 1 aberta por devolução |
| `WebhookResendIT` | 8 tentativas com backoff/jitter ⇒ `DEAD`; reenvio manual mantém `source_event_id` |
| `PiiAccessAuditIT` | Resposta com CPF completo grava `audit_log` |

### 3.7 E2E (`*IT`, top)
App completa (`@SpringBootTest(webEnvironment=RANDOM_PORT)`) + Postgres + Kafka + SR + Debezium + WireMock:
1. **Recebedor**: criar chave (DICT mock) → `PUT /cob` → publicar `SpiPaymentSettled` no Kafka → `GET /pix/{e2e}` → cobrança `CONCLUIDA` → webhook recebido no WireMock (1×) → evento em `pix.received.v1`.
2. **Devolução**: parcial + parcial + excedente (422) → `pacs.004` simulado → `DEVOLVIDO`.
3. **Pagador**: `POST /payment-orders` por chave (DICT+ledger mock) → `SENT_TO_SPI` → `SpiPaymentStatus` liquidado → `SETTLED`; variante rejeitada (`AM04`) libera reserva.
4. **MED 2.0**: abrir caso → bloqueio parcial → avançar camada → devolução `MED_FRAUDE` → contestação dentro dos 80 dias.
5. **Agendado**: agendar → data chega (relógio controlado) → ordem → `SETTLED`; variante saldo insuficiente → retry 19:00.
6. **Pix Automático**: solicitação → aceite → cobrança → tentativa → `PAGA`.
+ **Smoke de contexto** (`ApplicationContextIT`): sobe com Testcontainers e valida que **todos** os `UseCase` têm bean em `UseCaseConfig` (falha de wiring aparece aqui, não em produção).

### 3.8 Concorrência (`@Tag("concurrency")`)
`ChargeVersionConcurrencyTest`, `RefundSumConcurrencyTest` (N threads, Σ ≤ valor), `LimitConsumptionConcurrencyTest`, `IdempotencyRaceConcurrencyTest` (**exatamente 1×201**, N−1 replays/409), `InboxRaceConcurrencyTest` (2 consumers, mesma mensagem ⇒ 1 efeito), `SweeperSkipLockedConcurrencyTest`.

### 3.9 Performance (Gatling, `-Pperf`)
`PixManagerSimulation`: `PUT /cob` (rampa 0→300 rps em 2 min, sustenta 15 min), `GET /cob/{txid}`, `POST /payment-orders` com `Idempotency-Key` repetida; **asserts**: `global().responseTime().percentile(95).lt(150)`, `failedRequests().percent().lt(0.1)`; ao final, consulta lag e exige `< 1000`. Perfil `perf` **nunca** ativável em ambiente compartilhado; `hibernate.SQL=WARN`.

## 4. Qualidade e portões de CI
| Portão | Regra |
|---|---|
| Unit + slice | `mvn test` < 3 min; falha = PR bloqueado |
| Integração | `mvn verify` (Failsafe) |
| Cobertura | JaCoCo `check` com limite real: **linha ≥ 85% core / ≥ 70% global**, ramo ≥ 75% no `core`; relatório **merge** unit+IT (o report no `test` subconta IT) |
| Mutação (opcional) | PIT no `core.domain` (≥ 70%) |
| Arquitetura | ArchUnit dentro de `mvn test` |
| Segurança | `dependency-check` CVSS < 7; `gitleaks` |
| Contratos | OpenAPI/Avro/AsyncAPI diffs revisados |
| Noturno | suíte completa + concorrência + Gatling reduzido |

## 5. Definition of Done por camada
| Camada | DoD |
|---|---|
| Unit | Toda classe com regra tem teste; VOs com tabela de limites; sem mocks de tipos do domínio |
| Web | Cada endpoint com 2xx + 400 + 401 + 403 + (404/409/412/428 quando aplicável) |
| Persistência | Container real; Liquibase real; asserts no **estado persistido** |
| Mensageria | Duplicata, retry→DLT, negócio→DLT direta, poison pill, schema compat e pipeline Debezium provados |
| Contrato | Falhas 404/422/429/5xx/timeout testadas com **estado** resultante |
| E2E | Fluxos 1–4 verdes sem mocks internos |
| Concorrência | 6 testes acima verdes no job noturno |
| Perf | Asserts do Gatling verdes em ambiente dedicado |

## 6. Backlog de testes (de propósito, ainda não)
Chaos (Toxiproxy em Kafka/PG), teste de failover do PostgreSQL, restore PITR automatizado, fuzz de payload BR Code, contract tests consumer-driven (Pact) com consumidores reais dos eventos.
