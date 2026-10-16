--liquibase formatted sql

--changeset pix-manager:007-ledger-reservation
CREATE TABLE ledger_reservation (
    id               uuid           PRIMARY KEY,
    idempotency_key  varchar(64)    NOT NULL,             -- endToEndId da operação (pagamento/devolução); 'med-<caseId>-<n>' p/ bloqueio MED
    account_id       uuid           NOT NULL REFERENCES payment_account (id),
    purpose          varchar(15)    NOT NULL CHECK (purpose IN ('PAYMENT', 'REFUND', 'MED_BLOCK')),
    reference_type   varchar(20)    NOT NULL,
    reference_id     uuid           NOT NULL,
    amount           numeric(15,2)  NOT NULL CHECK (amount > 0),
    status           varchar(10)    NOT NULL CHECK (status IN ('PENDING', 'RESERVED', 'CONFIRMED', 'CANCELLED', 'FAILED')),   -- reservar -> confirmar | cancelar
    ledger_ref       varchar(100),
    attempts         smallint       NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz,
    last_error       varchar(500),
    version          bigint         NOT NULL DEFAULT 0,
    created_at       timestamptz    NOT NULL DEFAULT now(),
    updated_at       timestamptz    NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_ledger_reservation_ref ON ledger_reservation (reference_type, reference_id, purpose);
CREATE UNIQUE INDEX uk_ledger_reservation_idem ON ledger_reservation (idempotency_key, purpose);   -- API do core é idempotente por esta chave
CREATE INDEX idx_ledger_reservation_sweeper ON ledger_reservation (next_attempt_at) WHERE status IN ('PENDING', 'RESERVED');
--rollback DROP TABLE ledger_reservation;

--changeset pix-manager:007-trusted-device
CREATE TABLE trusted_device (
    id             uuid          PRIMARY KEY,
    account_id     uuid          NOT NULL REFERENCES payment_account (id),
    device_hash    varchar(64)      NOT NULL,               -- HMAC do identificador do dispositivo
    status         varchar(10)   NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED')),
    registered_at  timestamptz   NOT NULL,
    revoked_at     timestamptz,
    version        bigint        NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_trusted_device ON trusted_device (account_id, device_hash);
--rollback DROP TABLE trusted_device;

--changeset pix-manager:007-payment-order
CREATE TABLE payment_order (
    id                      uuid           PRIMARY KEY,
    payer_account_id        uuid           NOT NULL REFERENCES payment_account (id),
    payment_method          varchar(15)    NOT NULL CHECK (payment_method IN ('KEY', 'MANUAL', 'QR_STATIC', 'QR_DYNAMIC')),
    origin                  varchar(10)    NOT NULL DEFAULT 'IMMEDIATE' CHECK (origin IN ('IMMEDIATE', 'SCHEDULED', 'AUTOMATIC')),
    scheduled_occurrence_id uuid,                          -- FK em 011
    automatic_attempt_id    uuid,                          -- FK em 011
    device_registered       boolean        NOT NULL DEFAULT true,   -- dispositivo não cadastrado => limites reduzidos
    device_hash             varchar(64),
    destination_key_type    varchar(5),
    destination_key_hash    varchar(64),
    dest_document_type      varchar(4)     CHECK (dest_document_type IN ('CPF', 'CNPJ')),
    dest_document_enc       text,
    dest_document_hash      varchar(64),
    dest_document_masked    varchar(20),
    dest_name               varchar(200),
    dest_ispb               varchar(8),
    dest_branch             varchar(4),
    dest_account            varchar(20),
    dest_account_type       varchar(4),
    amount                  numeric(12,2)  NOT NULL CHECK (amount > 0),
    qr_txid                 varchar(35),
    qr_location_url         varchar(2048),
    payer_message           varchar(140),
    end_to_end_id           varchar(32)       NOT NULL CHECK (end_to_end_id ~ '^[a-zA-Z0-9]{32}$'),   -- gerado na criação: é a chave de idempotência do ledger
    status                  varchar(20)    NOT NULL CHECK (status IN (
                                'CREATED', 'VALIDATED', 'RESERVED', 'SENT_TO_SPI', 'SETTLED',
                                'REJECTED', 'FAILED_TECHNICAL', 'CANCELLED')),
    reject_reason_code      varchar(10),                       -- ISO 20022 (AC03, AM04, AB03...)
    reject_reason_detail    varchar(255),
    ledger_reservation_id   uuid           REFERENCES ledger_reservation (id),
    requested_at            timestamptz    NOT NULL,
    sent_at                 timestamptz,
    settled_at              timestamptz,
    version                 bigint         NOT NULL DEFAULT 0,
    created_at              timestamptz    NOT NULL DEFAULT now(),
    created_by              varchar(100)   NOT NULL,
    updated_at              timestamptz    NOT NULL DEFAULT now(),
    updated_by              varchar(100)   NOT NULL,
    CONSTRAINT ck_payment_order_rejected CHECK ((status = 'REJECTED') = (reject_reason_code IS NOT NULL)),
    CONSTRAINT ck_payment_order_destination CHECK (
        payment_method <> 'KEY' OR (destination_key_type IS NOT NULL AND destination_key_hash IS NOT NULL))
);
CREATE UNIQUE INDEX uk_payment_order_e2e ON payment_order (end_to_end_id);
CREATE INDEX idx_payment_order_payer ON payment_order (payer_account_id, created_at DESC);
CREATE INDEX idx_payment_order_inflight ON payment_order (updated_at) WHERE status IN ('RESERVED', 'SENT_TO_SPI');  -- sweeper
CREATE INDEX idx_payment_order_dest_doc ON payment_order (dest_document_hash) WHERE dest_document_hash IS NOT NULL;
--rollback DROP TABLE payment_order;

--changeset pix-manager:007-spi-message
CREATE TABLE spi_message (
    id               uuid          PRIMARY KEY,
    direction        varchar(3)    NOT NULL CHECK (direction IN ('IN', 'OUT')),
    message_type     varchar(10)   NOT NULL CHECK (message_type IN ('pacs.008', 'pacs.002', 'pacs.004', 'pacs.028', 'camt.056')),
    msg_id           varchar(35)   NOT NULL,
    end_to_end_id    varchar(32),
    payment_order_id uuid          REFERENCES payment_order (id),
    status           varchar(15)   NOT NULL CHECK (status IN ('QUEUED', 'SENT', 'ACKED', 'REJECTED', 'RECEIVED')),
    payload_hash     varchar(64)      NOT NULL,
    summary          jsonb,                                  -- campos não sensíveis
    occurred_at      timestamptz   NOT NULL,
    created_at       timestamptz   NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_spi_message_dir_msg ON spi_message (direction, msg_id);
CREATE INDEX idx_spi_message_e2e ON spi_message (end_to_end_id) WHERE end_to_end_id IS NOT NULL;
--rollback DROP TABLE spi_message;

--changeset pix-manager:007-pix-limit
CREATE TABLE pix_limit (
    id                   uuid           PRIMARY KEY,
    account_id           uuid           NOT NULL REFERENCES payment_account (id),
    scope                varchar(30)    NOT NULL CHECK (scope IN ('PER_TRANSACTION', 'DAILY', 'NIGHTLY', 'NEW_PAYEE',
                                'DEVICE_UNREGISTERED_PER_TX', 'DEVICE_UNREGISTERED_DAILY')),
    amount               numeric(15,2)  NOT NULL CHECK (amount >= 0),
    ceiling_amount       numeric(15,2),                  -- teto definido pelo banco (cliente altera até aqui)
    pending_amount       numeric(15,2),                  -- aumento solicitado: vale após 24h–48h (RN-LIM-03)
    pending_requested_at timestamptz,
    pending_effective_at timestamptz,
    version              bigint         NOT NULL DEFAULT 0,
    created_at           timestamptz    NOT NULL DEFAULT now(),
    created_by           varchar(100)   NOT NULL,
    updated_at           timestamptz    NOT NULL DEFAULT now(),
    updated_by           varchar(100)   NOT NULL,
    CONSTRAINT ck_pix_limit_pending CHECK ((pending_amount IS NULL) = (pending_effective_at IS NULL)),
    CONSTRAINT ck_pix_limit_ceiling CHECK (ceiling_amount IS NULL OR amount <= ceiling_amount)
);
CREATE UNIQUE INDEX uk_pix_limit_account_scope ON pix_limit (account_id, scope);
--rollback DROP TABLE pix_limit;

--changeset pix-manager:007-limit-usage
CREATE TABLE limit_usage (
    account_id   uuid           NOT NULL REFERENCES payment_account (id),
    usage_date   date           NOT NULL,                -- dia em America/Sao_Paulo
    scope        varchar(30)    NOT NULL CHECK (scope IN ('DAILY', 'NIGHTLY', 'DEVICE_UNREGISTERED_DAILY')),
    used_amount  numeric(15,2)  NOT NULL DEFAULT 0 CHECK (used_amount >= 0),
    version      bigint         NOT NULL DEFAULT 0,
    PRIMARY KEY (account_id, usage_date, scope)
);
COMMENT ON TABLE limit_usage IS 'Consumo atômico: UPDATE ... SET used_amount = used_amount + :v WHERE used_amount + :v <= :limit.';
--rollback DROP TABLE limit_usage;
