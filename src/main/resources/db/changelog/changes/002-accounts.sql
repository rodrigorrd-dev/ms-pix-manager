--liquibase formatted sql

--changeset pix-manager:002-account-holder
CREATE TABLE account_holder (
    id                 uuid         PRIMARY KEY,
    holder_type        varchar(4)   NOT NULL CHECK (holder_type IN ('CPF', 'CNPJ')),
    document_enc       text         NOT NULL,                       -- AES-256-GCM (iv||ciphertext, base64)
    document_hash      varchar(64)     NOT NULL,                       -- HMAC-SHA256 hex (índice cego)
    document_masked    varchar(20)  NOT NULL,                       -- ***.456.789-**
    name               varchar(200) NOT NULL,
    status             varchar(20)  NOT NULL CHECK (status IN ('ACTIVE', 'BLOCKED', 'CLOSED')),
    version            bigint       NOT NULL DEFAULT 0,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    created_by         varchar(100) NOT NULL,
    updated_at         timestamptz  NOT NULL DEFAULT now(),
    updated_by         varchar(100) NOT NULL
);
CREATE UNIQUE INDEX uk_account_holder_document_hash ON account_holder (document_hash);
COMMENT ON COLUMN account_holder.document_hash IS 'HMAC-SHA256(documento normalizado, chave de índice cego). Busca por igualdade.';
--rollback DROP TABLE account_holder;

--changeset pix-manager:002-payment-account
CREATE TABLE payment_account (
    id              uuid         PRIMARY KEY,
    holder_id       uuid         NOT NULL REFERENCES account_holder (id),
    ispb            varchar(8)      NOT NULL CHECK (ispb ~ '^[0-9]{8}$'),
    branch          varchar(4)   NOT NULL CHECK (branch ~ '^[0-9]{1,4}$'),
    account_number  varchar(20)  NOT NULL CHECK (account_number ~ '^[0-9A-Za-z-]{1,20}$'),
    account_type    varchar(4)   NOT NULL CHECK (account_type IN ('CACC', 'SVGS', 'TRAN', 'SLRY')), -- ISO 20022
    status          varchar(20)  NOT NULL CHECK (status IN ('ACTIVE', 'BLOCKED', 'CLOSED')),
    opened_at       timestamptz  NOT NULL,
    version         bigint       NOT NULL DEFAULT 0,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    created_by      varchar(100) NOT NULL,
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    updated_by      varchar(100) NOT NULL
);
CREATE UNIQUE INDEX uk_payment_account_identity ON payment_account (ispb, branch, account_number, account_type);
CREATE INDEX idx_payment_account_holder ON payment_account (holder_id);
--rollback DROP TABLE payment_account;
