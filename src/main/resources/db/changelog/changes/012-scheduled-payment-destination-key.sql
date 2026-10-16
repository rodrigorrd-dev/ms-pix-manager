--liquibase formatted sql

--changeset pix-manager:012-scheduled-payment-destination-key
-- O worker precisa da CHAVE para consultar o DICT no dia da execução; o DDL original guardava só o índice cego. Cifrada (PII), como pix_key.
ALTER TABLE scheduled_payment ADD COLUMN destination_key_enc text;
--rollback ALTER TABLE scheduled_payment DROP COLUMN destination_key_enc;
