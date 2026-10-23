--liquibase formatted sql

--changeset pix-manager:015-performance-indexes
-- GET /cob/{txid} sem o documento do recebedor: o único índice com txid tem receiver_document_hash na frente.
CREATE INDEX idx_pix_charge_txid ON pix_charge (txid);

-- Pix Agendado: listByPayer ordena por created_at; o índice (payer_account_id, status) não cobre a ordenação.
CREATE INDEX idx_scheduled_payment_payer_created ON scheduled_payment (payer_account_id, created_at DESC);

-- Pix Automático: listAuthorizations (por conta e no escopo total do back-office) ordena por created_at.
CREATE INDEX idx_pix_auth_payer_created ON pix_automatic_authorization (payer_account_id, created_at DESC);
CREATE INDEX idx_pix_auth_created ON pix_automatic_authorization (created_at DESC, id);

-- MED: list ordena por reported_at (por conta e no escopo total).
CREATE INDEX idx_med_case_account_reported ON med_case (account_id, reported_at DESC);
CREATE INDEX idx_med_case_reported ON med_case (reported_at DESC, id);

-- Webhooks: findByPixKeyId (FK sem índice) e a listagem filtrada/ordenada por created_at.
CREATE INDEX idx_webhook_subscription_pix_key ON webhook_subscription (pix_key_id);
CREATE INDEX idx_webhook_subscription_created ON webhook_subscription (created_at DESC, id);

-- Ordens de pagamento: a consulta filtra e ordena por requested_at, não por created_at.
CREATE INDEX idx_payment_order_payer_requested ON payment_order (payer_account_id, requested_at DESC);

-- OperationalMetrics (a cada 30 s): VALIDATED não estava no índice parcial de ordens em voo; DEAD não tinha índice nenhum.
CREATE INDEX idx_payment_order_validated ON payment_order (updated_at) WHERE status = 'VALIDATED';
CREATE INDEX idx_webhook_delivery_dead ON webhook_delivery (id) WHERE status = 'DEAD';

-- Retenção (RetentionCleanupJob): entregas de webhook já terminadas, localizadas por updated_at.
CREATE INDEX idx_webhook_delivery_finished ON webhook_delivery (updated_at) WHERE status IN ('DELIVERED', 'DEAD');
--rollback DROP INDEX idx_webhook_delivery_finished; DROP INDEX idx_webhook_delivery_dead; DROP INDEX idx_payment_order_validated; DROP INDEX idx_payment_order_payer_requested; DROP INDEX idx_webhook_subscription_created; DROP INDEX idx_webhook_subscription_pix_key; DROP INDEX idx_med_case_reported; DROP INDEX idx_med_case_account_reported; DROP INDEX idx_pix_auth_created; DROP INDEX idx_pix_auth_payer_created; DROP INDEX idx_scheduled_payment_payer_created; DROP INDEX idx_pix_charge_txid;
