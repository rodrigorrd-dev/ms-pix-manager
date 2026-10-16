--liquibase formatted sql

--changeset pix-manager:008-med-case
CREATE TABLE med_case (
    id                       uuid           PRIMARY KEY,
    dict_funds_recovery_id   varchar(36),                    -- id do DICT (funds-recoveries/{id})
    end_to_end_id            varchar(32)       NOT NULL,
    received_id              uuid           REFERENCES pix_received (id),
    payment_order_id         uuid           REFERENCES payment_order (id),
    case_type                varchar(20)    NOT NULL CHECK (case_type IN ('FRAUD', 'OPERATIONAL_FAILURE')),
    role                     varchar(10)    NOT NULL CHECK (role IN ('REPORTER', 'REPORTED')),
    status                   varchar(15)    NOT NULL CHECK (status IN (
                                 'OPEN', 'ACCEPTED', 'REFUNDING', 'CLOSED', 'REFUSED', 'CANCELLED', 'CONTESTED')),
    layer                    smallint       NOT NULL DEFAULT 1 CHECK (layer >= 1),   -- camada do rastreamento (MED 2.0)
    parent_case_id           uuid           REFERENCES med_case (id),                -- caso da camada anterior
    description              varchar(2000)  NOT NULL,
    reported_at              timestamptz    NOT NULL,
    analysis_deadline_at     timestamptz    NOT NULL,
    blocked_amount           numeric(12,2)  NOT NULL DEFAULT 0 CHECK (blocked_amount >= 0),
    version                  bigint         NOT NULL DEFAULT 0,
    created_at               timestamptz    NOT NULL DEFAULT now(),
    created_by               varchar(100)   NOT NULL,
    updated_at               timestamptz    NOT NULL DEFAULT now(),
    updated_by               varchar(100)   NOT NULL
);
CREATE INDEX idx_med_case_parent ON med_case (parent_case_id) WHERE parent_case_id IS NOT NULL;
CREATE UNIQUE INDEX uk_med_case_dict ON med_case (dict_funds_recovery_id, role) WHERE dict_funds_recovery_id IS NOT NULL;
CREATE INDEX idx_med_case_e2e ON med_case (end_to_end_id);
CREATE INDEX idx_med_case_open_deadline ON med_case (analysis_deadline_at) WHERE status IN ('OPEN', 'ACCEPTED', 'REFUNDING');
--rollback DROP TABLE med_case;

--changeset pix-manager:008-med-case-history
CREATE TABLE med_case_history (
    id           uuid          PRIMARY KEY,
    case_id      uuid          NOT NULL REFERENCES med_case (id),
    from_status  varchar(15),
    to_status    varchar(15)   NOT NULL,
    actor        varchar(100)  NOT NULL,
    note         varchar(500),
    occurred_at  timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX idx_med_case_history_case ON med_case_history (case_id, occurred_at);
CREATE TRIGGER trg_med_case_history_append_only
    BEFORE UPDATE OR DELETE ON med_case_history
    FOR EACH ROW EXECUTE FUNCTION fn_append_only_guard();
--rollback DROP TABLE med_case_history;

--changeset pix-manager:008-med-block
CREATE TABLE med_block (
    id                     uuid           PRIMARY KEY,
    case_id                uuid           NOT NULL REFERENCES med_case (id),
    account_id             uuid           NOT NULL REFERENCES payment_account (id),
    amount                 numeric(12,2)  NOT NULL CHECK (amount > 0),
    ledger_reservation_id  uuid           NOT NULL REFERENCES ledger_reservation (id),
    status                 varchar(10)    NOT NULL CHECK (status IN ('ACTIVE', 'RELEASED', 'CONSUMED')),
    created_at             timestamptz    NOT NULL DEFAULT now(),
    updated_at             timestamptz    NOT NULL DEFAULT now(),
    version                bigint         NOT NULL DEFAULT 0
);
CREATE INDEX idx_med_block_case ON med_block (case_id);
--rollback DROP TABLE med_block;

--changeset pix-manager:008-med-hop
-- Rastreamento em camadas: cada salto dos recursos para a conta seguinte; bloqueios/devoluções parciais conforme entra saldo.
CREATE TABLE med_hop (
    id                uuid           PRIMARY KEY,
    case_id           uuid           NOT NULL REFERENCES med_case (id),
    layer             smallint       NOT NULL CHECK (layer >= 1),
    origin_e2e_id     varchar(32)       NOT NULL,                -- Pix que moveu os recursos a esta camada
    next_e2e_id       varchar(32),                               -- Pix que os levou à camada seguinte (se houve)
    tracked_amount    numeric(12,2)  NOT NULL CHECK (tracked_amount > 0),
    blocked_amount    numeric(12,2)  NOT NULL DEFAULT 0 CHECK (blocked_amount >= 0),
    refunded_amount   numeric(12,2)  NOT NULL DEFAULT 0 CHECK (refunded_amount >= 0),
    status            varchar(12)    NOT NULL CHECK (status IN ('TRACKING', 'PARTIAL', 'RECOVERED', 'EXHAUSTED', 'EXPIRED')),
    window_ends_at    timestamptz    NOT NULL,                -- fim da janela (até 90 dias da transação original)
    version           bigint         NOT NULL DEFAULT 0,
    created_at        timestamptz    NOT NULL DEFAULT now(),
    updated_at        timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT ck_med_hop_amounts CHECK (blocked_amount + refunded_amount <= tracked_amount)
);
CREATE INDEX idx_med_hop_case ON med_hop (case_id, layer);
CREATE INDEX idx_med_hop_open ON med_hop (window_ends_at) WHERE status IN ('TRACKING', 'PARTIAL');
--rollback DROP TABLE med_hop;

--changeset pix-manager:008-med-contestation
-- Contestação, pelo recebedor, de devolução feita via MED: até 80 dias a contar da devolução (regra vigente desde 2026-09-01).
CREATE TABLE med_contestation (
    id               uuid           PRIMARY KEY,
    case_id          uuid           NOT NULL REFERENCES med_case (id),
    refund_id        uuid           NOT NULL REFERENCES pix_refund (id),
    status           varchar(12)    NOT NULL CHECK (status IN ('OPEN', 'ACCEPTED', 'REJECTED', 'CANCELLED')),
    reason           varchar(2000)  NOT NULL,
    refunded_at      timestamptz    NOT NULL,                 -- início da contagem dos 80 dias
    deadline_at      timestamptz    NOT NULL,
    decided_at       timestamptz,
    version          bigint         NOT NULL DEFAULT 0,
    created_at       timestamptz    NOT NULL DEFAULT now(),
    created_by       varchar(100)   NOT NULL,
    updated_at       timestamptz    NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_med_contestation_refund_open ON med_contestation (refund_id) WHERE status = 'OPEN';
CREATE INDEX idx_med_contestation_deadline ON med_contestation (deadline_at) WHERE status = 'OPEN';
--rollback DROP TABLE med_contestation;
