--liquibase formatted sql

--changeset pix-manager:010-scheduled-payment
-- Pix Agendado (lado pagador): único ou recorrente.
CREATE TABLE scheduled_payment (
    id                    uuid           PRIMARY KEY,
    payer_account_id      uuid           NOT NULL REFERENCES payment_account (id),
    recurrence            varchar(10)    NOT NULL CHECK (recurrence IN ('ONCE', 'WEEKLY', 'BIWEEKLY', 'MONTHLY', 'BIMONTHLY', 'QUARTERLY', 'SEMIANNUAL', 'ANNUAL')),
    payment_method        varchar(15)    NOT NULL CHECK (payment_method IN ('KEY', 'MANUAL', 'QR_STATIC', 'QR_DYNAMIC')),
    destination_key_type  varchar(5),
    destination_key_hash  varchar(64),
    dest_document_type    varchar(4)     CHECK (dest_document_type IN ('CPF', 'CNPJ')),
    dest_document_enc     text,
    dest_document_hash    varchar(64),
    dest_document_masked  varchar(20),
    dest_name             varchar(200),
    dest_ispb             varchar(8),
    dest_branch           varchar(4),
    dest_account          varchar(20),
    dest_account_type     varchar(4),
    amount                numeric(12,2)  NOT NULL CHECK (amount > 0),
    payer_message         varchar(140),
    first_date            date           NOT NULL,                -- dia em America/Sao_Paulo
    end_date              date,
    occurrences_total     integer        CHECK (occurrences_total > 0),
    status                varchar(10)    NOT NULL CHECK (status IN ('ACTIVE', 'COMPLETED', 'CANCELLED')),
    device_registered     boolean        NOT NULL DEFAULT true,
    version               bigint         NOT NULL DEFAULT 0,
    created_at            timestamptz    NOT NULL DEFAULT now(),
    created_by            varchar(100)   NOT NULL,
    updated_at            timestamptz    NOT NULL DEFAULT now(),
    updated_by            varchar(100)   NOT NULL,
    CONSTRAINT ck_scheduled_once CHECK (recurrence <> 'ONCE' OR (end_date IS NULL AND occurrences_total IS NULL OR occurrences_total = 1))
);
CREATE INDEX idx_scheduled_payment_payer ON scheduled_payment (payer_account_id, status);
--rollback DROP TABLE scheduled_payment;

--changeset pix-manager:010-scheduled-occurrence
CREATE TABLE scheduled_payment_occurrence (
    id                    uuid           PRIMARY KEY,
    scheduled_payment_id  uuid           NOT NULL REFERENCES scheduled_payment (id),
    sequence_no           integer        NOT NULL CHECK (sequence_no >= 1),
    due_date              date           NOT NULL,                -- data de execução
    status                varchar(25)    NOT NULL CHECK (status IN (
                              'SCHEDULED', 'PROCESSING', 'EXECUTED', 'RETRY_PENDING',
                              'FAILED_INSUFFICIENT_FUNDS', 'FAILED', 'CANCELLED')),
    attempt_count         smallint       NOT NULL DEFAULT 0,
    next_attempt_at       timestamptz,                            -- retry 18h–21h (RN-SCH-03)
    customer_notified_at  timestamptz,                            -- aviso de saldo insuficiente
    payment_order_id      uuid           REFERENCES payment_order (id),
    last_error_code       varchar(20),
    version               bigint         NOT NULL DEFAULT 0,
    created_at            timestamptz    NOT NULL DEFAULT now(),
    updated_at            timestamptz    NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_scheduled_occurrence_seq ON scheduled_payment_occurrence (scheduled_payment_id, sequence_no);
-- worker: SELECT ... WHERE status IN ('SCHEDULED','RETRY_PENDING') AND (due_date <= :today) AND (next_attempt_at IS NULL OR next_attempt_at <= now()) FOR UPDATE SKIP LOCKED
CREATE INDEX idx_scheduled_occurrence_due ON scheduled_payment_occurrence (due_date, next_attempt_at) WHERE status IN ('SCHEDULED', 'RETRY_PENDING');
--rollback DROP TABLE scheduled_payment_occurrence;

--changeset pix-manager:010-payment-order-fks
ALTER TABLE payment_order ADD CONSTRAINT fk_payment_order_occurrence FOREIGN KEY (scheduled_occurrence_id) REFERENCES scheduled_payment_occurrence (id);
CREATE INDEX idx_payment_order_occurrence ON payment_order (scheduled_occurrence_id) WHERE scheduled_occurrence_id IS NOT NULL;
--rollback ALTER TABLE payment_order DROP CONSTRAINT fk_payment_order_occurrence; DROP INDEX idx_payment_order_occurrence;

--changeset pix-manager:010-pix-automatic-authorization
-- Pix Automático (lado pagador): autorização (recorrência) enviada pelo recebedor (PJ) e aceita pelo pagador.
-- Estados e ids conforme API Pix 2.10.0 (RecStatus, SolicRecStatus, RecId).
CREATE TABLE pix_automatic_authorization (
    id                        uuid           PRIMARY KEY,
    id_rec                    varchar(29)       NOT NULL CHECK (id_rec ~ '^R[RN][a-zA-Z0-9]{27}$'),   -- R + (R|N) + ISPB/CNPJ8 + yyyyMMdd + 11
    allow_retry               boolean        NOT NULL,                                              -- 2º caractere do idRec: R=permite retentativa pós-vencimento
    payer_account_id          uuid           NOT NULL REFERENCES payment_account (id),
    payer_document_hash       varchar(64)       NOT NULL,                                              -- CPF/CNPJ do devedor (deve igualar o titular)
    receiver_document_enc     text           NOT NULL,
    receiver_document_hash    varchar(64)       NOT NULL,
    receiver_document_masked  varchar(20)    NOT NULL,
    receiver_name             varchar(200)   NOT NULL,
    contract                  varchar(35)    NOT NULL,
    object_description        varchar(35),
    periodicity               varchar(10)    NOT NULL CHECK (periodicity IN ('SEMANAL', 'MENSAL', 'TRIMESTRAL', 'SEMESTRAL', 'ANUAL')),
    start_date                date           NOT NULL,
    end_date                  date,
    fixed_amount              numeric(12,2),                    -- valorRec (valor fixo)
    receiver_min_amount       numeric(12,2),                    -- valorMinimoRecebedor
    payer_max_amount          numeric(12,2),                    -- teto definido pelo pagador
    status                    varchar(10)    NOT NULL CHECK (status IN ('CRIADA', 'APROVADA', 'REJEITADA', 'EXPIRADA', 'CANCELADA')),
    request_status            varchar(10)    NOT NULL CHECK (request_status IN ('CRIADA', 'ENVIADA', 'RECEBIDA', 'REJEITADA', 'ACEITA', 'EXPIRADA', 'CANCELADA')),
    rejection_code            varchar(4)     CHECK (rejection_code IN ('AP13', 'AP14', 'AP15')),
    cancelled_by              varchar(10)    CHECK (cancelled_by IN ('PAYER', 'RECEIVER', 'PSP')),
    approved_at               timestamptz,
    cancelled_at              timestamptz,
    request_expires_at        timestamptz,
    version                   bigint         NOT NULL DEFAULT 0,
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(100)   NOT NULL,
    updated_at                timestamptz    NOT NULL DEFAULT now(),
    updated_by                varchar(100)   NOT NULL,
    CONSTRAINT ck_pix_auth_amounts CHECK (payer_max_amount IS NULL OR receiver_min_amount IS NULL OR payer_max_amount >= receiver_min_amount),
    CONSTRAINT ck_pix_auth_approved CHECK ((status = 'APROVADA') = (approved_at IS NOT NULL) OR status IN ('CANCELADA', 'EXPIRADA'))
);
CREATE UNIQUE INDEX uk_pix_auth_id_rec ON pix_automatic_authorization (id_rec);
CREATE INDEX idx_pix_auth_payer ON pix_automatic_authorization (payer_account_id, status);
CREATE INDEX idx_pix_auth_request_expiry ON pix_automatic_authorization (request_expires_at) WHERE request_status IN ('RECEBIDA', 'ENVIADA');
--rollback DROP TABLE pix_automatic_authorization;

--changeset pix-manager:010-pix-automatic-charge
CREATE TABLE pix_automatic_charge (
    id                uuid           PRIMARY KEY,
    authorization_id  uuid           NOT NULL REFERENCES pix_automatic_authorization (id),
    txid              varchar(35)    NOT NULL CHECK (txid ~ '^[a-zA-Z0-9]{26,35}$'),
    due_date          date           NOT NULL,
    amount            numeric(12,2)  NOT NULL CHECK (amount > 0),
    status            varchar(10)    NOT NULL CHECK (status IN ('CRIADA', 'ATIVA', 'CONCLUIDA', 'EXPIRADA', 'REJEITADA', 'CANCELADA')),
    version           bigint         NOT NULL DEFAULT 0,
    created_at        timestamptz    NOT NULL DEFAULT now(),
    updated_at        timestamptz    NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_pix_auto_charge_txid ON pix_automatic_charge (authorization_id, txid);
CREATE INDEX idx_pix_auto_charge_due ON pix_automatic_charge (due_date) WHERE status IN ('CRIADA', 'ATIVA');
--rollback DROP TABLE pix_automatic_charge;

--changeset pix-manager:010-pix-automatic-attempt
CREATE TABLE pix_automatic_attempt (
    id                uuid           PRIMARY KEY,
    charge_id         uuid           NOT NULL REFERENCES pix_automatic_charge (id),
    attempt_type      varchar(4)     NOT NULL CHECK (attempt_type IN ('AGND', 'NTAG', 'RIFL')),     -- tipo da tentativa (API Pix)
    status            varchar(10)    NOT NULL CHECK (status IN ('SOLICITADA', 'AGENDADA', 'PAGA', 'CANCELADA', 'REJEITADA', 'EXPIRADA')),
    end_to_end_id     varchar(32)       NOT NULL CHECK (end_to_end_id ~ '^[a-zA-Z0-9]{32}$'),
    settlement_date   date           NOT NULL,
    payment_order_id  uuid           REFERENCES payment_order (id),
    created_at        timestamptz    NOT NULL DEFAULT now(),
    updated_at        timestamptz    NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_pix_auto_attempt_e2e ON pix_automatic_attempt (end_to_end_id);
CREATE INDEX idx_pix_auto_attempt_charge ON pix_automatic_attempt (charge_id, settlement_date);
CREATE INDEX idx_pix_auto_attempt_open ON pix_automatic_attempt (settlement_date) WHERE status IN ('SOLICITADA', 'AGENDADA');
--rollback DROP TABLE pix_automatic_attempt;

--changeset pix-manager:010-payment-order-attempt-fk
ALTER TABLE payment_order ADD CONSTRAINT fk_payment_order_auto_attempt FOREIGN KEY (automatic_attempt_id) REFERENCES pix_automatic_attempt (id);
--rollback ALTER TABLE payment_order DROP CONSTRAINT fk_payment_order_auto_attempt;
