# Pagamento preso (`PixPaymentsStuck`)

**Sintoma:** `pix_payment_orders_stuck{status}` > 0 por 10 min: ordens em `VALIDATED`, `RESERVED` ou `SENT_TO_SPI` sem avançar há mais de 5 min.
**Impacto:** cliente não vê o Pix concluído; saldo pode estar reservado sem liquidação.

## Quem faz cada passagem (para achar o elo quebrado)
| Estado | Avança por | Se travar, suspeite de |
|---|---|---|
| `VALIDATED → RESERVED` | `PendingReservationJob` chama o **core-ledger** | core indisponível / breaker aberto ([circuit-breaker-aberto.md](circuit-breaker-aberto.md)) |
| `RESERVED → SENT_TO_SPI` | mensagem `SENT` em `pix.spi.payment-status.v1` (do **spi-gateway**) | evento `PaymentReserved` não saiu (Debezium/outbox) ou spi-gateway parado |
| `SENT_TO_SPI → SETTLED` / `REJECTED` | mensagem `SETTLED`/`REJECTED` em `pix.spi.payment-status.v1` | SPI sem resposta, mensagem na DLT |

## Diagnóstico
```sql
-- quais e há quanto tempo
select id, status, end_to_end_id, updated_at, now()-updated_at as parado
  from payment_order where status in ('VALIDATED','RESERVED','SENT_TO_SPI') and updated_at < now()-interval '5 minutes' order by updated_at limit 50;
-- reserva no core desse pagamento
select status, attempts, last_error from ledger_reservation where idempotency_key = '<end_to_end_id>';
-- o evento saiu? (aggregate_id = id da ordem)
select event_type, created_at from outbox_event where aggregate_id = '<id>' order by created_at;
```
Depois: conector Debezium `RUNNING`? ([debezium-slot-lag.md](debezium-slot-lag.md)); lag do consumer `ms-pix-manager.payment-status`; DLT ([mensagem-na-dlt.md](mensagem-na-dlt.md)).

## Ação
- `RESERVED` por falha do SPI/spi-gateway: o saldo continua reservado. **Não** cancele manualmente depois de `SENT_TO_SPI` (o dinheiro já foi para o SPI). Até `RESERVED` é possível cancelar: `POST /v1/payment-orders/{id}/cancellation` com `If-Match`.
- Mensagem de status perdida/atrasada: reenviada pelo spi-gateway é segura — o inbox (`messageId`) garante efeito único.
- **Dev:** sem spi-gateway, toda ordem fica em `RESERVED` e este alerta dispara por desenho; use `scripts/simulate-spi.py payment-status <e2e> SENT|SETTLED`.

## Escalonamento
Ordem presa em `SENT_TO_SPI` > 30 min em produção: time do spi-gateway e conciliação (dinheiro pode ter saído).
