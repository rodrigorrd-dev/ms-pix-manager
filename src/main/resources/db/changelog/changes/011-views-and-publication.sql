--liquibase formatted sql

--changeset pix-manager:011-mv-reconciliation-daily
CREATE MATERIALIZED VIEW mv_pix_reconciliation_daily AS
SELECT (settled_at AT TIME ZONE 'America/Sao_Paulo')::date AS day,
       reconciliation_status,
       count(*)      AS received_count,
       sum(amount)   AS received_amount
  FROM pix_received
 GROUP BY 1, 2;
CREATE UNIQUE INDEX uk_mv_pix_reconciliation_daily ON mv_pix_reconciliation_daily (day, reconciliation_status);  -- exigido p/ REFRESH CONCURRENTLY
--rollback DROP MATERIALIZED VIEW mv_pix_reconciliation_daily;

--changeset pix-manager:011-outbox-publication runOnChange:false
-- Publicação lógica usada pelo Debezium (plugin pgoutput). Requer wal_level=logical (ver infra/docker-compose.yml).
CREATE PUBLICATION pix_outbox_pub FOR TABLE outbox_event;
--rollback DROP PUBLICATION pix_outbox_pub;
