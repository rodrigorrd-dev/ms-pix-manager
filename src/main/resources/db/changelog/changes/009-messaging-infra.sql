--liquibase formatted sql

--changeset pix-manager:009-outbox-event
-- Escrita pelo adaptador de outbox na MESMA transação do agregado. Lida pelo Debezium (EventRouter) via WAL.
CREATE TABLE outbox_event (
    id             uuid          PRIMARY KEY,                 -- eventId (vai no header, base da idempotência do consumidor)
    aggregate_type varchar(50)   NOT NULL,                    -- roteia o tópico: pix.<aggregate_type>.v1
    aggregate_id   varchar(64)   NOT NULL,                    -- chave da mensagem Kafka (ordem por agregado)
    event_type     varchar(100)  NOT NULL,
    event_version  smallint      NOT NULL DEFAULT 1,
    payload        bytea         NOT NULL,                    -- Avro (wire format Confluent: 0x00 + schemaId + dados)
    trace_parent   varchar(55),                               -- W3C traceparent
    created_at     timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX idx_outbox_event_created ON outbox_event (created_at);   -- purge
COMMENT ON TABLE outbox_event IS 'Insert-only. Purge diário (>3 dias) por job; o Debezium não depende das linhas após a captura.';
--rollback DROP TABLE outbox_event;

--changeset pix-manager:009-inbox-message
CREATE TABLE inbox_message (
    id             uuid          PRIMARY KEY,
    consumer_group varchar(100)  NOT NULL,
    message_id     varchar(100)  NOT NULL,                    -- header eventId; fallback topic-partition-offset
    topic          varchar(200)  NOT NULL,
    kafka_partition integer      NOT NULL,
    kafka_offset   bigint        NOT NULL,
    processed_at   timestamptz   NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_inbox_consumer_message ON inbox_message (consumer_group, message_id);
CREATE INDEX idx_inbox_processed ON inbox_message (processed_at);     -- purge (retenção 14d > retenção do tópico)
--rollback DROP TABLE inbox_message;

--changeset pix-manager:009-idempotency-record
CREATE TABLE idempotency_record (
    id               uuid          PRIMARY KEY,
    client_id        varchar(100)  NOT NULL,
    operation        varchar(100)  NOT NULL,                  -- ex.: POST /v1/payment-orders
    idempotency_key  varchar(255)  NOT NULL,
    request_hash     varchar(64)      NOT NULL,
    status           varchar(12)   NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    locked_until     timestamptz,                             -- lease: falha técnica NÃO queima a chave
    response_status  smallint,
    response_headers jsonb,
    response_body    jsonb,
    created_at       timestamptz   NOT NULL DEFAULT now(),
    expires_at       timestamptz   NOT NULL
);
CREATE UNIQUE INDEX uk_idempotency_scope ON idempotency_record (client_id, operation, idempotency_key);
CREATE INDEX idx_idempotency_expires ON idempotency_record (expires_at);
--rollback DROP TABLE idempotency_record;

--changeset pix-manager:009-audit-log
CREATE TABLE audit_log (
    id            uuid          PRIMARY KEY,
    actor         varchar(100)  NOT NULL,
    action        varchar(100)  NOT NULL,
    entity_type   varchar(50)   NOT NULL,
    entity_id     varchar(64)   NOT NULL,
    before_state  jsonb,                                       -- sem PII em claro
    after_state   jsonb,
    trace_id      varchar(32),
    occurred_at   timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_log_entity ON audit_log (entity_type, entity_id, occurred_at DESC);
CREATE TRIGGER trg_audit_log_append_only
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION fn_append_only_guard();
--rollback DROP TABLE audit_log;

--changeset pix-manager:009-shedlock
CREATE TABLE shedlock (
    name        varchar(64)   PRIMARY KEY,
    lock_until  timestamp(3)  NOT NULL,
    locked_at   timestamp(3)  NOT NULL,
    locked_by   varchar(255)  NOT NULL
);
--rollback DROP TABLE shedlock;
