# 06 — Eventos e mensageria (Kafka + Avro + Schema Registry + Debezium)

> Skills: `resilience-patterns`, `cqrs-architecture`, `logging-patterns`, `concurrency-review`, `test-pyramid`.
> Decisões do solicitante: Kafka + Schema Registry + Kafka UI no docker-compose; producer **idempotente** com **Avro**; consumer com **retry/DLT** e **inbox**; **Outbox com Debezium**; tracing e métricas de lag; **Springwolf**; testes com **Testcontainers**.

## 1. Visão

```
 REST/Consumer ─► UseCase ─► [TX: agregado + outbox_event(Avro bytes)] ─COMMIT─► PostgreSQL
                                                                              │ WAL (pgoutput, publication pix_outbox_pub)
                                                                              ▼
                         Debezium Connect (EventRouter SMT) ──► Kafka  pix.<aggregate_type>.v1  (key = aggregate_id)
                                                                              │  Avro + Schema Registry (BACKWARD_TRANSITIVE)
                                                                              ▼
                              consumidores (inclusive este ms: webhook, notificação, read models)
 spi-gateway ─► Kafka pix.spi.*.v1 ─► Consumer (inbox → UseCase → outbox) …  falha técnica ─► retry topics ─► DLT
```

## 2. Catálogo de tópicos

Padrão: `pix.<domínio>.<fato>.v<major>`; saída = **fato no passado**; entrada de terceiros = prefixo `pix.spi.` / `pix.dict.`. Chave da mensagem = `aggregate_id` (ordem por agregado). Cleanup `delete` salvo indicação.

| Tópico | Direção | Produtor → Consumidor | Chave | Partições | Retenção | Schema (subject `<topic>-value`) |
|---|---|---|---|---|---|---|
| `pix.charge.v1` | saída (outbox) | ms → webhook/BI | `charge.id` | 12 | 7 d | `ChargeEvent` |
| `pix.received.v1` | saída | ms → webhook/BI/contábil | `e2eId` | 12 | 7 d | `PixReceivedEvent` |
| `pix.refund.v1` | saída | ms → webhook/BI | `refund.id` | 12 | 7 d | `RefundEvent` |
| `pix.payment.v1` | saída | ms → app/notificação | `paymentOrder.id` | 12 | 7 d | `PaymentOrderEvent` |
| `pix.key.v1` | saída | ms → BI/auditoria | `pixKey.id` | 6 | 30 d (compact+delete) | `PixKeyEvent` |
| `pix.med.v1` | saída | ms → antifraude/BI | `medCase.id` | 6 | 30 d | `MedCaseEvent` (inclui `layer`, `hop`, `contestation`) |
| `pix.schedule.v1` | saída | ms → notificação/BI | `scheduledPayment.id` | 6 | 7 d | `ScheduledPaymentEvent` (inclui `RetryNotified`) |
| `pix.automatic.v1` | saída | ms → notificação/BI (+ `spi-gateway` p/ resposta ao recebedor) | `idRec` | 6 | 30 d | `AutomaticAuthorizationEvent` / `AutomaticAttemptEvent` |
| `pix.limit.v1` | saída | ms → auditoria/notificação | `accountId` | 3 | 30 d | `LimitChangedEvent` |
| `pix.spi.payment-settled.v1` | **entrada** | spi-gateway → ms | `e2eId` | 12 | 7 d | `SpiPaymentSettled` |
| `pix.spi.payment-status.v1` | entrada | spi-gateway → ms (pacs.002) | `e2eId` | 12 | 7 d | `SpiPaymentStatus` |
| `pix.spi.refund-status.v1` | entrada | spi-gateway → ms (pacs.004) | `rtrId` | 6 | 7 d | `SpiRefundStatus` |
| `pix.dict.key-event.v1` | entrada | dict-gateway (notificações DICT) → ms | `keyHash` | 6 | 7 d | `DictKeyEvent` |
| `pix.spi.automatic-request.v1` | entrada | spi-gateway → ms (solicitação/autorização do recebedor) | `idRec` | 6 | 7 d | `SpiAutomaticRequest` |
| `pix.spi.automatic-charge.v1` | entrada | spi-gateway → ms (cobrança recorrente e retentativas) | `idRec` | 6 | 7 d | `SpiAutomaticCharge` |
| `pix.spi.med-notification.v1` | entrada | spi-gateway/dict-gateway → ms (infração recebida, camada) | `e2eId` | 6 | 30 d | `SpiMedNotification` |
| `<topic>-retry-0..2` | interno | `@RetryableTopic` | — | = origem | 1 d | = origem |
| `<topic>.dlt` | interno | consumer → operações | = origem | 3 | 30 d | = origem |
Dimensionamento: partições = múltiplo do nº máx. de réplicas × 2; **aumentar partições quebra ordem por chave** ⇒ decidir agora (12). `replication.factor=3`, `min.insync.replicas=2` em produção (1/1 no compose local).

## 3. Contratos Avro (`src/main/resources/avro/*.avsc`)

Regras de schema:
0. **`ispb` da instituição vai no envelope** (vem de `pix.institution.ispb`, nunca fixo no código — decisão A3).
1. **Compatibilidade `BACKWARD_TRANSITIVE`** por subject (consumidores novos leem dados antigos). Só se adiciona campo **com default**; remover campo só se tinha default; **nunca** mudar tipo/nome.
2. **Envelope comum** (`EventEnvelope`: `eventId` uuid, `eventType`, `eventVersion`, `occurredAt` `timestamp-micros`, `aggregateId`, `correlationId`, `traceId`) embutido em cada evento; o `eventId` = `outbox_event.id` = **chave de idempotência do consumidor**.
3. Dinheiro: `bytes` + `logicalType: decimal(precision 15, scale 2)`; tempo: `long` + `timestamp-micros`; ids: `string` + `logicalType: uuid`.
4. **Sem PII em claro**: `documentMasked`/`documentHash`; nunca nome completo + CPF juntos em eventos de BI.
5. Enums Avro **evitados** em evolução (adicionar símbolo quebra leitores antigos sem default); usar `string` + lista documentada, ou enum com `default` de símbolo `UNKNOWN`.
6. Namespace `br.com.pixmanager.events.v1`; classes geradas por `avro-maven-plugin` a partir do mesmo `.avsc` (single source of truth); `SpecificRecord`.
7. Teste: `SchemaCompatibilityIT` registra cada `.avsc` num Schema Registry (Testcontainers) e valida compat contra a versão **em `main`** (pipeline).

## 4. Producer

### 4.1 Eventos de domínio — via outbox/Debezium (não há `KafkaTemplate` no caminho de negócio)
- `OutboxEventPublisherAdapter` serializa o Avro com `KafkaAvroSerializer` (usa o Schema Registry; subject strategy `TopicNameStrategy` com o tópico de **destino** `pix.<aggregate_type>.v1`) e grava **bytes** em `outbox_event.payload` na mesma transação (ADR-005/006).
- Falha ao serializar/registrar schema ⇒ exceção **antes do commit** (a transação inteira falha — nunca evento perdido).
- Cabeçalhos Kafka via EventRouter: `id` (eventId), `eventType`, `traceparent`.

### 4.2 Producer idempotente (DLT e produtores diretos)
```yaml
spring.kafka.producer:
  acks: all
  properties:
    enable.idempotence: true            # PID + sequence: sem duplicata por retry interno
    max.in.flight.requests.per.connection: 5   # ≤5 preserva ordem com idempotência
    retries: 2147483647
    delivery.timeout.ms: 120000
    linger.ms: 5
    compression.type: lz4
    key.serializer: org.apache.kafka.common.serialization.StringSerializer
    value.serializer: io.confluent.kafka.serializers.KafkaAvroSerializer
    schema.registry.url: ${SCHEMA_REGISTRY_URL}
    auto.register.schemas: false        # schemas só entram pelo pipeline (governança)
    use.latest.version: true
    transactional.id: ${spring.application.name}-${HOSTNAME}   # só se usar EOS read-process-write
```
Notas: idempotência elimina duplicatas **da sessão do producer**; não substitui o inbox no consumidor (reinício do producer = novo PID). `auto.register.schemas=false` em produção.
O mesmo vale para o **Kafka Connect/Debezium**: `producer.override.acks=all`, `enable.idempotence=true` (padrão no Connect ≥ 3.x).

## 5. Debezium Outbox

`docker/debezium/outbox-connector.json` (EventRouter):
- `plugin.name=pgoutput`, `publication.name=pix_outbox_pub`, `slot.name=pix_outbox_slot`, `table.include.list=public.outbox_event`, `tombstones.on.delete=false`.
- `transforms=outbox` → `io.debezium.transforms.outbox.EventRouter`; `table.field.event.id=id`, `table.field.event.key=aggregate_id`, `table.field.event.payload=payload`, `route.by.field=aggregate_type`, `route.topic.replacement=pix.${routedByValue}.v1`, `table.fields.additional.placement=event_type:header:eventType,trace_parent:header:traceparent`.
- **Conversores pass-through**: `key.converter=StringConverter`, `value.converter=ByteArrayConverter` — os bytes já são Avro/wire-format (ADR-006). Consumidores usam `KafkaAvroDeserializer`.
- Slot de replicação **monitorado** (métrica `pg_replication_slot_lag_bytes`; alerta > 512 MB) — slot órfão enche o disco do WAL.
- `snapshot.mode=no_data` (outbox não precisa de snapshot histórico), `heartbeat.interval.ms=10000` (evita retenção de WAL em tabelas ociosas; os registros de heartbeat são STRUCT e **precisam ser descartados** por `Filter`+`TopicNameMatches(__debezium-heartbeat.*)` antes do EventRouter, senão o `ByteArrayConverter` derruba a task), sem `table.field.event.timestamp` (o `timestamptz` chega como string ISO e o EventRouter exige INT64; usa-se o timestamp do registro), **linhas inseridas antes de o slot existir não são capturadas** (`no_data`), `errors.tolerance=none`, `errors.deadletterqueue` **não** usado (falha de conversão deve parar e alertar).
- Conector criado/atualizado por script idempotente (`docker/debezium/register-connector.sh`), versionado.

## 6. Consumer

### 6.1 Fluxo (um listener = um `UseCase`)
```
poll → deserializa Avro (falha de schema → DLT direto) → TX { INSERT inbox ON CONFLICT DO NOTHING
        → se 0 linhas: ack (duplicata) → senão: UseCase.execute(...) (+ outbox) } COMMIT → ack offset
```
- `enable.auto.commit=false`, `ack-mode=RECORD` (commit do offset **depois** do commit do banco). Falha entre os dois ⇒ reentrega ⇒ inbox bloqueia o efeito duplicado.
- `isolation.level=read_committed` (se o produtor for transacional), `max.poll.records` moderado, `max.poll.interval.ms` > pior caso de processamento.
- **Sem chamada HTTP dentro da transação do consumidor** (DICT/ledger): o consumidor grava a intenção + outbox/`ledger_reservation` e um worker faz a chamada.

### 6.2 Retry / DLT (resilience-patterns aplicado)
| Tipo de falha | Exemplos | Ação |
|---|---|---|
| **Negócio / dado inválido** | schema inesperado, `e2eId` inválido, CPF inválido, estado inválido | **Sem retry** → DLT imediata (`@RetryableTopic(exclude = {BusinessException.class, SerializationException.class})`) com motivo |
| **Técnica transitória** | timeout, `CannotAcquireLock`, deadlock, 5xx do ledger | retry **não bloqueante**: `-retry-0` (5 s) → `-retry-1` (30 s) → `-retry-2` (3 min), backoff exponencial **com jitter**, depois DLT |
| **Poison pill** | bytes não-Avro | `ErrorHandlingDeserializer` + DLT, nunca loop infinito |
- **Como declarar um consumer:** `@PixKafkaListener(topic = TOPIC, group = CONSUMER_GROUP)` (anotação composta em `api/messaging`) = `@KafkaListener` + a política `@RetryableTopic` acima, lida de `pix.kafka.retry.*`; a política vive num lugar só. O método `@DltHandler` fica no próprio consumer e delega a `DltMetrics.onDeadLetter(record)` (métrica `pix_dlt_messages_total` + log).
- DLT guarda cabeçalhos: `dlt-original-topic/partition/offset`, `dlt-exception-fqcn/message` (sem PII), `dlt-attempts`, `traceparent`.
- **Reprocessamento da DLT** por endpoint administrativo `POST /v1/admin/dlt/{topic}/replay` (autorização `ops.dlt`), que republica no tópico original com `eventId` **preservado** (o inbox decide se já foi aplicado).
- Retry em tópico separado **quebra a ordem por chave** ⇒ válido porque cada handler é idempotente e baseado em estado (`ReceivePix` por `e2eId`, transições `canTransitionTo`; eventos fora de ordem são **ignorados com log** quando o estado alvo já foi superado — BR-GEN).
- `configurações`: `concurrency` = nº de partições / réplicas; virtual threads **não** substituem partições.

### 6.3 Inbox
`InboxGuard.firstTime(consumerGroup, messageId)`: `INSERT … ON CONFLICT (consumer_group, message_id) DO NOTHING` ⇒ retorna se inseriu. `messageId` = header `id` (eventId); para tópicos de terceiros sem id = `"<topic>-<partition>-<offset>"` (estável em reentrega). Purga por `processed_at` (14 d). Métrica `inbox_duplicates_total{consumer}`.

## 7. Observabilidade da mensageria
| Sinal | Como |
|---|---|
| **Tracing** | `spring.kafka.template.observation-enabled=true`, `spring.kafka.listener.observation-enabled=true`; `traceparent` persistido em `outbox_event.trace_parent` e repassado como header pelo Debezium ⇒ **um trace** HTTP → DB → outbox → Kafka → consumer (`07`). |
| **Lag de consumer** | Micrometer `kafka_consumer_fetch_manager_records_lag{client_id,topic,partition}` / `…_records_lag_max` (client) **e** `kafka_consumergroup_lag` do **kafka-exporter** (broker-side, vê grupos parados). Alertas: lag > 1.000 msgs **ou** > 30 s por 5 min; lag crescente com consumo = 0. |
| **Throughput/erro** | `spring_kafka_listener_seconds_*` (duração), `pix_consumer_failures_total{kind=technical|business}`, `pix_dlt_messages_total{topic}` (alerta > 0 em 5 min), `inbox_duplicates_total`. |
| **Outbox** | `pix_outbox_pending` (linhas com `created_at < now()-30s` ainda não vistas pelo Debezium não é mensurável direto ⇒ usar lag do slot): `pg_replication_slot_lag_bytes` e `debezium_metrics_MilliSecondsBehindSource` (JMX do Connect). |
| **Producer** | `kafka_producer_record_error_total`, `…_request_latency_avg`. |
| **UI** | Kafka UI (tópicos, grupos, schemas, DLT). |
Dashboards Grafana versionados em `ops/grafana/` (backlog de implementação, definição aqui).

## 8. Springwolf (AsyncAPI)
- `springwolf-kafka` + `springwolf-ui`: `springwolf.docket.base-package=br.com.pixmanager.api.consumer`, `info.title=ms-pix-manager`, `servers.kafka-server.protocol=kafka`.
- `@AsyncListener(operation=@AsyncOperation(channelName="pix.spi.payment-settled.v1", description=…, payloadType=SpiPaymentSettled.class))` nos consumers; `@AsyncPublisher` no adaptador de outbox (documenta canais de saída mesmo sendo Debezium quem publica — nota na descrição).
- Endpoints: `/springwolf/docs` (JSON) e `/springwolf/asyncapi-ui.html`; **protegidos** em `prd` (mesma regra do Swagger).
- Teste `AsyncApiContractTest` salva o JSON gerado e compara com `docs/api/asyncapi/asyncapi.json` (diff no PR).

## 9b. Contrato de integração (rascunho — `10` E2)
| Fluxo | Mecanismo |
|---|---|
| ms → core (reservar/confirmar/cancelar) | HTTP idempotente por `endToEndId`; chamado por **worker** a partir de `ledger_reservation` (nunca dentro da TX de negócio) |
| ms → DICT | HTTP **somente via `dict-gateway`** (limite, cache, retentativas no gateway); 429/5xx = técnico |
| ms ⇄ SPI | **somente Kafka** via `spi-gateway` (pacs.* ⇄ Avro). `pix.payment.v1` (status `RESERVED`/`SENT_TO_SPI`) é lido pelo gateway para emitir pacs.008; `pix.spi.payment-status.v1` devolve pacs.002 |
| Ajuste MED 2.0 (26/10/2026) | `SpiMedNotification.layer` obrigatório; ms propaga `layer` ao encaminhar |

## 9. Garantias e limites (honesto)
| Garantia | Como | Limite |
|---|---|---|
| Evento nunca perdido | outbox transacional + WAL | falha do Connect atrasa, não perde; slot precisa de monitoramento |
| Sem evento fantasma | evento só existe se o commit ocorreu | — |
| Entrega **at-least-once** ao consumidor | Kafka + commit após efeito | duplicatas possíveis ⇒ inbox |
| Efeito **exatamente-uma-vez** no ms | inbox + constraints únicas | só para efeitos **dentro do PostgreSQL**; HTTP externo usa chave de idempotência própria |
| Ordem por agregado | chave de partição | quebrada por retry topics ⇒ handlers tolerantes |
