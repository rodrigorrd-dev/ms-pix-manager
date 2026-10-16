--liquibase formatted sql

--changeset pix-manager:005-pix-received
CREATE TABLE pix_received (
    id                      uuid          PRIMARY KEY,
    end_to_end_id           varchar(32)      NOT NULL CHECK (end_to_end_id ~ '^[a-zA-Z0-9]{32}$'),
    txid                    varchar(35)   CHECK (txid ~ '^[a-zA-Z0-9]{1,35}$'),
    charge_id               uuid          REFERENCES pix_charge (id),
    receiver_account_id     uuid          NOT NULL REFERENCES payment_account (id),
    pix_key_id              uuid          REFERENCES pix_key (id),
    amount                  numeric(12,2) NOT NULL CHECK (amount >= 0),
    refundable_amount       numeric(12,2) NOT NULL CHECK (refundable_amount >= 0),
    refunded_total          numeric(12,2) NOT NULL DEFAULT 0,
    payer_type              varchar(4)    CHECK (payer_type IN ('CPF', 'CNPJ')),
    payer_document_enc      text,
    payer_document_hash     varchar(64),
    payer_document_masked   varchar(20),
    payer_name              varchar(200),
    payer_ispb              varchar(8),
    payer_branch            varchar(4),
    payer_account           varchar(20),
    payer_account_type      varchar(4),
    payer_info              varchar(140),                -- infoPagador
    settled_at              timestamptz   NOT NULL,      -- "horario" (liquidação no SPI, UTC)
    reconciliation_status   varchar(30)   NOT NULL CHECK (reconciliation_status IN (
                                'NO_CHARGE', 'MATCHED', 'MATCHED_AFTER_EXPIRATION', 'MISMATCH')),
    source_message_id       varchar(100)  NOT NULL,      -- id do evento SPI (rastreio)
    version                 bigint        NOT NULL DEFAULT 0,
    created_at              timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ck_pix_received_refund_cap CHECK (refunded_total <= refundable_amount)
);
CREATE UNIQUE INDEX uk_pix_received_e2e ON pix_received (end_to_end_id);               -- RN-PIX-01 (idempotência)
CREATE INDEX idx_pix_received_txid ON pix_received (txid) WHERE txid IS NOT NULL;
CREATE INDEX idx_pix_received_account_settled ON pix_received (receiver_account_id, settled_at DESC);
CREATE INDEX idx_pix_received_payer_doc ON pix_received (payer_document_hash, settled_at DESC) WHERE payer_document_hash IS NOT NULL;
CREATE INDEX idx_pix_received_recon ON pix_received (reconciliation_status, settled_at DESC) WHERE reconciliation_status <> 'MATCHED';
COMMENT ON COLUMN pix_received.refunded_total IS 'Atualizado atomicamente: UPDATE ... SET refunded_total = refunded_total + :v WHERE refunded_total + :v <= refundable_amount.';
--rollback DROP TABLE pix_received;

--changeset pix-manager:005-pix-charge-paid-fk
-- DEFERRABLE: cobrança e Pix são gravados na MESMA transação (conciliação), em qualquer ordem; a FK é conferida no commit.
ALTER TABLE pix_charge ADD CONSTRAINT fk_pix_charge_paid_received FOREIGN KEY (paid_received_id) REFERENCES pix_received (id)
    DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX idx_pix_charge_paid_received ON pix_charge (paid_received_id) WHERE paid_received_id IS NOT NULL;
--rollback ALTER TABLE pix_charge DROP CONSTRAINT fk_pix_charge_paid_received; DROP INDEX idx_pix_charge_paid_received;

--changeset pix-manager:005-pix-received-component
CREATE TABLE pix_received_component (
    received_id                 uuid          NOT NULL REFERENCES pix_received (id),
    component_type              varchar(10)   NOT NULL CHECK (component_type IN (
                                    'ORIGINAL', 'WITHDRAWAL', 'CHANGE', 'FINE', 'INTEREST', 'REBATE', 'DISCOUNT')),
    amount                      numeric(12,2) NOT NULL CHECK (amount >= 0),
    agent_modality              varchar(5),            -- AGTEC | AGTOT | AGFSS  (saque/troco)
    withdrawal_provider_ispb    varchar(8),
    PRIMARY KEY (received_id, component_type)
);
COMMENT ON TABLE pix_received_component IS 'valor = ORIGINAL + WITHDRAWAL + CHANGE + FINE + INTEREST - REBATE - DISCOUNT (API Pix 2.10.0).';
--rollback DROP TABLE pix_received_component;

--changeset pix-manager:005-pix-refund
CREATE TABLE pix_refund (
    id                      uuid          PRIMARY KEY,
    refund_id               varchar(35)   NOT NULL CHECK (refund_id ~ '^[a-zA-Z0-9]{1,35}$'),   -- id do cliente
    received_id             uuid          NOT NULL REFERENCES pix_received (id),
    end_to_end_id           varchar(32)      NOT NULL,
    rtr_id                  varchar(32)      CHECK (rtr_id ~ '^[a-zA-Z0-9]{32}$'),                  -- pacs.004
    amount                  numeric(12,2) NOT NULL CHECK (amount > 0),
    nature                  varchar(20)   NOT NULL DEFAULT 'ORIGINAL' CHECK (nature IN (
                                'ORIGINAL', 'RETIRADA', 'MED_OPERACIONAL', 'MED_FRAUDE', 'MED_PIX_AUTOMATICO')),
    description             varchar(140),
    status                  varchar(20)   NOT NULL CHECK (status IN ('EM_PROCESSAMENTO', 'DEVOLVIDO', 'NAO_REALIZADO')),
    status_reason           varchar(140),
    request_hash            varchar(64)      NOT NULL,       -- idempotência: mesmo id + corpo diferente => 422
    ledger_reservation_id   uuid,
    requested_at            timestamptz   NOT NULL,
    settled_at              timestamptz,
    version                 bigint        NOT NULL DEFAULT 0,
    created_at              timestamptz   NOT NULL DEFAULT now(),
    created_by              varchar(100)  NOT NULL,
    updated_at              timestamptz   NOT NULL DEFAULT now(),
    updated_by              varchar(100)  NOT NULL,
    CONSTRAINT ck_pix_refund_settled CHECK ((status = 'DEVOLVIDO') = (settled_at IS NOT NULL))
);
CREATE UNIQUE INDEX uk_pix_refund_e2e_refund ON pix_refund (end_to_end_id, refund_id);
CREATE UNIQUE INDEX uk_pix_refund_rtr ON pix_refund (rtr_id) WHERE rtr_id IS NOT NULL;
CREATE INDEX idx_pix_refund_received ON pix_refund (received_id);
CREATE INDEX idx_pix_refund_pending ON pix_refund (requested_at) WHERE status = 'EM_PROCESSAMENTO';   -- sweeper
--rollback DROP TABLE pix_refund;
