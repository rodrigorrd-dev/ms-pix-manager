--liquibase formatted sql

--changeset pix-manager:006-webhook-subscription
CREATE TABLE webhook_subscription (
    id             uuid           PRIMARY KEY,
    pix_key_id     uuid           NOT NULL REFERENCES pix_key (id),
    key_hash       varchar(64)       NOT NULL,
    url            varchar(2048)  NOT NULL CHECK (url ~ '^https://'),
    secret_ref     varchar(100),                      -- referência ao cofre de segredos (HMAC/mTLS); nunca o segredo
    status         varchar(10)    NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    verified_at    timestamptz,
    version        bigint         NOT NULL DEFAULT 0,
    created_at     timestamptz    NOT NULL DEFAULT now(),
    created_by     varchar(100)   NOT NULL,
    updated_at     timestamptz    NOT NULL DEFAULT now(),
    updated_by     varchar(100)   NOT NULL
);
CREATE UNIQUE INDEX uk_webhook_subscription_key ON webhook_subscription (key_hash);   -- RN-WHK-01
--rollback DROP TABLE webhook_subscription;

--changeset pix-manager:006-webhook-delivery
CREATE TABLE webhook_delivery (
    id                 uuid          PRIMARY KEY,
    subscription_id    uuid          NOT NULL REFERENCES webhook_subscription (id),
    source_event_id    uuid          NOT NULL,                -- id do evento da outbox (idempotência)
    event_type         varchar(60)   NOT NULL,
    payload            jsonb         NOT NULL,                -- corpo BCB do webhook; PII mínima
    status             varchar(10)   NOT NULL CHECK (status IN ('PENDING', 'RETRYING', 'DELIVERED', 'DEAD')),
    attempts           smallint      NOT NULL DEFAULT 0,
    next_attempt_at    timestamptz   NOT NULL,
    last_http_status   smallint,
    last_error         varchar(500),
    delivered_at       timestamptz,
    version            bigint        NOT NULL DEFAULT 0,
    created_at         timestamptz   NOT NULL DEFAULT now(),
    updated_at         timestamptz   NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_webhook_delivery_event ON webhook_delivery (subscription_id, source_event_id);
-- dispatcher: SELECT ... WHERE status IN ('PENDING','RETRYING') AND next_attempt_at <= now() FOR UPDATE SKIP LOCKED
CREATE INDEX idx_webhook_delivery_due ON webhook_delivery (next_attempt_at) WHERE status IN ('PENDING', 'RETRYING');
--rollback DROP TABLE webhook_delivery;
