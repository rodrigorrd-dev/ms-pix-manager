# Outbox / Debezium atrasado (`PixOutboxSlotLag`, `PixOutboxBacklog`)

**Sintoma:** `pix_outbox_slot_lag_bytes` > 512 MB por 5 min, ou `pix_outbox_rows` > 200 mil.
**Impacto:** eventos de domínio (`pix.*.v1`) deixam de chegar ao Kafka ⇒ spi-gateway não recebe ordens de pagamento/devoluções. **Risco grave:** o slot de replicação segura o WAL; se o Connect ficar parado, o disco do Postgres enche e o **banco para**.

## Diagnóstico
```bash
curl -s $CONNECT_URL/connectors/pix-outbox-connector/status | jq     # connector e task devem estar RUNNING
docker exec pix-postgres psql -U pix -d pixmanager -c "select slot_name, active, pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn)) lag from pg_replication_slots;"
docker exec pix-postgres psql -U pix -d pixmanager -c "select count(*), min(created_at) from outbox_event;"
```
- `active = f` ⇒ o Connect não está conectado ao slot.
- `pix_outbox_slot_lag_bytes = -1` ⇒ o usuário do app não pode ler o WAL; use o `postgres_exporter` e troque a métrica na regra.
- `pix_outbox_rows` alto com lag baixo ⇒ a limpeza (`OutboxCleanupJob`, retenção `PIX_OUTBOX_RETENTION`, padrão 7 d) não está rodando: veja o log `outbox:` e o ShedLock.

## Ação
1. Connect/task `FAILED`: `curl -X POST "$CONNECT_URL/connectors/pix-outbox-connector/restart?includeTasks=true&onlyFailed=true"`. Erro persistente: leia `trace` no status (credencial `debezium`, publicação `pix_outbox_pub` ausente, tópico inexistente).
2. Connect fora: suba-o; o slot retoma de onde parou (nenhum evento é perdido enquanto o slot existir).
3. **Emergência de disco** (WAL a ponto de encher): como último recurso, `select pg_drop_replication_slot('pix_outbox_slot')` **descarta os eventos ainda não publicados** — eles continuam na `outbox_event` e podem ser republicados por SQL (veja [backup-e-dr.md](backup-e-dr.md) §4.1, testado em dev). Decida com o dono do serviço antes. Prevenção: `max_slot_wal_keep_size` no Postgres.

## Escalonamento
Slot inativo > 15 min, ou disco do banco > 80%: DBA + plataforma imediatamente.
