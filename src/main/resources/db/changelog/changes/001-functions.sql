--liquibase formatted sql

--changeset pix-manager:001-append-only-guard splitStatements:false
-- Impede UPDATE/DELETE em tabelas append-only (auditoria, histórico MED).
CREATE OR REPLACE FUNCTION fn_append_only_guard() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Tabela % é append-only: % não permitido', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;
--rollback DROP FUNCTION IF EXISTS fn_append_only_guard();
