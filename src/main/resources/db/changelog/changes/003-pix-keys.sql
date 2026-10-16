--liquibase formatted sql

--changeset pix-manager:003-pix-key
CREATE TABLE pix_key (
    id                   uuid         PRIMARY KEY,
    account_id           uuid         NOT NULL REFERENCES payment_account (id),
    key_type             varchar(5)   NOT NULL CHECK (key_type IN ('CPF', 'CNPJ', 'PHONE', 'EMAIL', 'EVP')),
    key_value_enc        text         NOT NULL,                      -- valor da chave (PII p/ CPF, PHONE, EMAIL)
    key_hash             varchar(64)     NOT NULL,                      -- HMAC-SHA256 do valor normalizado
    key_masked           varchar(80)  NOT NULL,
    status               varchar(30)  NOT NULL CHECK (status IN (
                             'PENDING_OWNERSHIP_PROOF', 'PENDING_DICT', 'ACTIVE',
                             'PORTABILITY_REQUESTED', 'OWNERSHIP_CLAIMED', 'REJECTED', 'DELETED')),
    dict_cid             varchar(64),                                -- Content Identifier p/ reconciliação
    dict_registered_at   timestamptz,
    deleted_reason       varchar(30) CHECK (deleted_reason IN (
                             'USER_REQUESTED', 'ACCOUNT_CLOSURE', 'BRANCH_TRANSFER',
                             'RECONCILIATION', 'FRAUD', 'DEFAULT_OPERATION')),
    deleted_at           timestamptz,
    version              bigint       NOT NULL DEFAULT 0,
    created_at           timestamptz  NOT NULL DEFAULT now(),
    created_by           varchar(100) NOT NULL,
    updated_at           timestamptz  NOT NULL DEFAULT now(),
    updated_by           varchar(100) NOT NULL,
    CONSTRAINT ck_pix_key_deleted CHECK ((status = 'DELETED') = (deleted_at IS NOT NULL))
);
-- Uma chave ativa/em trânsito por valor em todo o sistema (RN-DOC-04). Excluídas liberam o valor.
CREATE UNIQUE INDEX uk_pix_key_live_hash ON pix_key (key_hash) WHERE status NOT IN ('DELETED', 'REJECTED');
CREATE INDEX idx_pix_key_account_status ON pix_key (account_id, status);
CREATE INDEX idx_pix_key_pending_dict ON pix_key (updated_at) WHERE status = 'PENDING_DICT';  -- sweeper
--rollback DROP TABLE pix_key;

--changeset pix-manager:003-pix-key-claim
CREATE TABLE pix_key_claim (
    id                       uuid         PRIMARY KEY,
    dict_claim_id            varchar(36)  NOT NULL,
    claim_type               varchar(12)  NOT NULL CHECK (claim_type IN ('OWNERSHIP', 'PORTABILITY')),
    role                     varchar(10)  NOT NULL CHECK (role IN ('CLAIMER', 'DONOR')),
    status                   varchar(20)  NOT NULL CHECK (status IN (
                                 'OPEN', 'WAITING_RESOLUTION', 'CONFIRMED', 'COMPLETED', 'CANCELLED')),
    key_type                 varchar(5)   NOT NULL,
    key_hash                 varchar(64)     NOT NULL,
    pix_key_id               uuid         REFERENCES pix_key (id),   -- chave local (doador) ou futura (reivindicador)
    account_id               uuid         NOT NULL REFERENCES payment_account (id),
    resolution_deadline_at   timestamptz  NOT NULL,                  -- RN-KEY-07 (parametrizado)
    completion_deadline_at   timestamptz  NOT NULL,
    cancel_reason            varchar(30),
    version                  bigint       NOT NULL DEFAULT 0,
    created_at               timestamptz  NOT NULL DEFAULT now(),
    created_by               varchar(100) NOT NULL,
    updated_at               timestamptz  NOT NULL DEFAULT now(),
    updated_by               varchar(100) NOT NULL
);
CREATE UNIQUE INDEX uk_pix_key_claim_dict ON pix_key_claim (dict_claim_id, role);
CREATE INDEX idx_pix_key_claim_expiration ON pix_key_claim (resolution_deadline_at)
    WHERE status IN ('OPEN', 'WAITING_RESOLUTION');
CREATE UNIQUE INDEX uk_pix_key_claim_open_per_key ON pix_key_claim (key_hash, role)
    WHERE status IN ('OPEN', 'WAITING_RESOLUTION', 'CONFIRMED');
--rollback DROP TABLE pix_key_claim;

--changeset pix-manager:003-otp-challenge
CREATE TABLE otp_challenge (
    id           uuid         PRIMARY KEY,
    pix_key_id   uuid         NOT NULL REFERENCES pix_key (id),
    channel      varchar(10)  NOT NULL CHECK (channel IN ('SMS', 'EMAIL')),
    code_hash    varchar(64)     NOT NULL,                 -- nunca o código em claro
    attempts     smallint     NOT NULL DEFAULT 0,
    expires_at   timestamptz  NOT NULL,
    verified_at  timestamptz,
    created_at   timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_otp_challenge_key ON otp_challenge (pix_key_id, created_at DESC);
--rollback DROP TABLE otp_challenge;

--changeset pix-manager:003-dict-sync-state
CREATE TABLE dict_sync_state (
    sync_scope     varchar(50)  PRIMARY KEY,           -- ex.: ENTRIES
    last_run_at    timestamptz,
    last_status    varchar(20)  CHECK (last_status IN ('OK', 'DIVERGENT', 'FAILED')),
    divergences    integer      NOT NULL DEFAULT 0,
    details        jsonb,
    updated_at     timestamptz  NOT NULL DEFAULT now()
);
--rollback DROP TABLE dict_sync_state;
