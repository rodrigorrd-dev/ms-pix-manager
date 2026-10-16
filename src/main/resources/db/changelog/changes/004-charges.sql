--liquibase formatted sql

--changeset pix-manager:004-pix-location
CREATE TABLE pix_location (
    id            uuid          PRIMARY KEY,
    location_id   bigint        NOT NULL,              -- id numérico exposto na API (/loc/{id})
    location_type varchar(4)    NOT NULL CHECK (location_type IN ('COB', 'COBV')),
    url_path      varchar(255)  NOT NULL,              -- pixUrlAccessToken
    txid          varchar(35),                         -- null quando desvinculada (DELETE /loc/{id}/txid)
    created_at    timestamptz   NOT NULL DEFAULT now(),
    unlinked_at   timestamptz
);
CREATE SEQUENCE seq_pix_location_id START 1 INCREMENT 1;
CREATE UNIQUE INDEX uk_pix_location_location_id ON pix_location (location_id);
CREATE UNIQUE INDEX uk_pix_location_url_path ON pix_location (url_path);
--rollback DROP SEQUENCE seq_pix_location_id; DROP TABLE pix_location;

--changeset pix-manager:004-pix-charge
CREATE TABLE pix_charge (
    id                      uuid          PRIMARY KEY,
    charge_type             varchar(9)    NOT NULL CHECK (charge_type IN ('IMMEDIATE', 'DUE')),
    txid                    varchar(35)   NOT NULL CHECK (txid ~ '^[a-zA-Z0-9]{26,35}$'),
    receiver_account_id     uuid          NOT NULL REFERENCES payment_account (id),
    receiver_document_hash  varchar(64)      NOT NULL,     -- escopo de unicidade do txid (API Pix 2.10)
    pix_key_id              uuid          NOT NULL REFERENCES pix_key (id),
    status                  varchar(40)   NOT NULL CHECK (status IN (
                                'ATIVA', 'CONCLUIDA', 'REMOVIDA_PELO_USUARIO_RECEBEDOR', 'REMOVIDA_PELO_PSP')),
    original_amount         numeric(12,2) NOT NULL CHECK (original_amount > 0),   -- \d{1,10}\.\d{2}
    expiration_seconds      integer       CHECK (expiration_seconds BETWEEN 1 AND 31536000),
    expires_at              timestamptz,                -- created_at + expiration (IMMEDIATE); calculado pela app com Clock
    due_date                date,
    validity_after_due_days integer       CHECK (validity_after_due_days >= 0),
    debtor_type             varchar(4)    CHECK (debtor_type IN ('CPF', 'CNPJ')),
    debtor_document_enc     text,
    debtor_document_hash    varchar(64),
    debtor_name             varchar(200),
    payer_request           varchar(140),               -- solicitacaoPagador
    location_id             uuid          REFERENCES pix_location (id),
    revision                integer       NOT NULL DEFAULT 0,
    paid_received_id        uuid,                       -- FK criada em 005 (dependência circular)
    removed_at              timestamptz,
    version                 bigint        NOT NULL DEFAULT 0,
    created_at              timestamptz   NOT NULL DEFAULT now(),
    created_by              varchar(100)  NOT NULL,
    updated_at              timestamptz   NOT NULL DEFAULT now(),
    updated_by              varchar(100)  NOT NULL,
    CONSTRAINT ck_pix_charge_immediate CHECK (charge_type <> 'IMMEDIATE'
        OR (expiration_seconds IS NOT NULL AND expires_at IS NOT NULL AND due_date IS NULL)),
    CONSTRAINT ck_pix_charge_due CHECK (charge_type <> 'DUE'
        OR (due_date IS NOT NULL AND validity_after_due_days IS NOT NULL AND expiration_seconds IS NULL)),
    CONSTRAINT ck_pix_charge_debtor CHECK (
        (debtor_type IS NULL AND debtor_document_enc IS NULL AND debtor_document_hash IS NULL)
        OR (debtor_type IS NOT NULL AND debtor_document_enc IS NOT NULL AND debtor_document_hash IS NOT NULL
            AND debtor_name IS NOT NULL)),
    CONSTRAINT ck_pix_charge_removed CHECK ((status LIKE 'REMOVIDA%') = (removed_at IS NOT NULL))
);
CREATE UNIQUE INDEX uk_pix_charge_receiver_txid ON pix_charge (receiver_document_hash, txid);
CREATE INDEX idx_pix_charge_search ON pix_charge (receiver_account_id, created_at DESC);
CREATE INDEX idx_pix_charge_status_created ON pix_charge (status, created_at DESC);
CREATE INDEX idx_pix_charge_debtor ON pix_charge (debtor_document_hash) WHERE debtor_document_hash IS NOT NULL;
CREATE INDEX idx_pix_charge_due_date ON pix_charge (due_date) WHERE charge_type = 'DUE' AND status = 'ATIVA';
CREATE INDEX idx_pix_charge_expires_active ON pix_charge (expires_at) WHERE charge_type = 'IMMEDIATE' AND status = 'ATIVA';
COMMENT ON TABLE pix_charge IS 'cob (IMMEDIATE) e cobv (DUE). Expiração NÃO é status (doc BCB): cobrança expirada segue ATIVA.';
--rollback DROP TABLE pix_charge;

--changeset pix-manager:004-pix-charge-due-terms
CREATE TABLE pix_charge_due_terms (
    charge_id          uuid          PRIMARY KEY REFERENCES pix_charge (id),
    fine_modality      smallint      CHECK (fine_modality BETWEEN 1 AND 2),         -- 1 valor fixo, 2 percentual
    fine_value         numeric(12,2),
    interest_modality  smallint      CHECK (interest_modality BETWEEN 1 AND 8),     -- modalidades da API Pix
    interest_value     numeric(12,2),
    rebate_modality    smallint      CHECK (rebate_modality BETWEEN 1 AND 2),
    rebate_value       numeric(12,2),
    discount_modality  smallint      CHECK (discount_modality BETWEEN 1 AND 6),
    discount_value     numeric(12,2),
    discount_schedule  jsonb,                                                         -- até 3 datas fixas (validado pela app)
    CONSTRAINT ck_due_terms_fine CHECK ((fine_modality IS NULL) = (fine_value IS NULL)),
    CONSTRAINT ck_due_terms_interest CHECK ((interest_modality IS NULL) = (interest_value IS NULL))
);
COMMENT ON TABLE pix_charge_due_terms IS 'Faixas das modalidades conforme API Pix 2.10.0 (reconferir tabela de modalidades em 09).';
--rollback DROP TABLE pix_charge_due_terms;

--changeset pix-manager:004-pix-charge-additional-info
CREATE TABLE pix_charge_additional_info (
    charge_id  uuid          NOT NULL REFERENCES pix_charge (id),
    position   smallint      NOT NULL CHECK (position BETWEEN 1 AND 50),
    name       varchar(50)   NOT NULL,
    value      varchar(200)  NOT NULL,
    PRIMARY KEY (charge_id, position)
);
--rollback DROP TABLE pix_charge_additional_info;

--changeset pix-manager:004-pix-charge-revision
CREATE TABLE pix_charge_revision (
    charge_id     uuid         NOT NULL REFERENCES pix_charge (id),
    revision      integer      NOT NULL,
    snapshot      jsonb        NOT NULL,           -- PII mascarada/ausente
    changed_by    varchar(100) NOT NULL,
    changed_at    timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (charge_id, revision)
);
--rollback DROP TABLE pix_charge_revision;
