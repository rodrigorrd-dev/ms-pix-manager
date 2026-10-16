--liquibase formatted sql

--changeset pix-manager:014-automatic-execution
-- Para pagar a cobrança o PSP do pagador precisa da conta do recebedor (vem na cobrança) e de controle de retentativa/versão por tentativa.
ALTER TABLE pix_automatic_charge ADD COLUMN receiver_ispb varchar(8);
ALTER TABLE pix_automatic_charge ADD COLUMN receiver_branch varchar(4);
ALTER TABLE pix_automatic_charge ADD COLUMN receiver_account varchar(20);
ALTER TABLE pix_automatic_charge ADD COLUMN receiver_account_type varchar(4);
ALTER TABLE pix_automatic_attempt ADD COLUMN last_error_code varchar(20);
ALTER TABLE pix_automatic_attempt ADD COLUMN version bigint NOT NULL DEFAULT 0;
--rollback ALTER TABLE pix_automatic_attempt DROP COLUMN last_error_code, DROP COLUMN version; ALTER TABLE pix_automatic_charge DROP COLUMN receiver_ispb, DROP COLUMN receiver_branch, DROP COLUMN receiver_account, DROP COLUMN receiver_account_type;
