--liquibase formatted sql

--changeset pix-manager:013-med-case-links
-- Conta do NOSSO cliente (controle de acesso), valor rastreado e a devolução MED vinculada ao caso.
ALTER TABLE med_case ADD COLUMN account_id uuid REFERENCES payment_account (id);
ALTER TABLE med_case ADD COLUMN tracked_amount numeric(12,2);
ALTER TABLE med_case ADD COLUMN refund_id uuid REFERENCES pix_refund (id);
CREATE INDEX idx_med_case_account ON med_case (account_id, status);
CREATE INDEX idx_med_case_refunding ON med_case (updated_at) WHERE status = 'REFUNDING';
--rollback ALTER TABLE med_case DROP COLUMN account_id, DROP COLUMN tracked_amount, DROP COLUMN refund_id;
