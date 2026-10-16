# Runbooks — ms-pix-manager

Um runbook por alerta (`ops/prometheus/alert-rules.yml` aponta para eles na anotação `runbook`). Formato fixo: **Sintoma → Impacto → Diagnóstico → Ação → Escalonamento**.

| Alerta | Severidade | Runbook |
|---|---|---|
| `PixAppDown` | crítico | [app-fora-do-ar.md](app-fora-do-ar.md) |
| `PixCircuitBreakerOpen`, `PixKeysStuckPendingDict`, `PixLedgerReservationsStuck` | crítico / aviso | [circuit-breaker-aberto.md](circuit-breaker-aberto.md) |
| `PixPaymentsStuck` | crítico | [pagamento-preso.md](pagamento-preso.md) |
| `PixDltMessages` | crítico | [mensagem-na-dlt.md](mensagem-na-dlt.md) |
| `PixWebhookDeliveriesDead`, `PixWebhookDeliveriesOverdue` | aviso | [webhook-entregas.md](webhook-entregas.md) |
| `PixOutboxSlotLag`, `PixOutboxBacklog` | crítico / aviso | [debezium-slot-lag.md](debezium-slot-lag.md) |
| `PixConsumerLagHigh`, `PixConsumerStuck` | aviso / crítico | [mensagem-na-dlt.md](mensagem-na-dlt.md) §Lag |
| `PixHighLatencyP95`, `PixErrorRate`, `PixDbPoolSaturated` | aviso / crítico | [app-fora-do-ar.md](app-fora-do-ar.md) §Degradação |

Procedimentos planejados (não são resposta a alerta): [rotacao-de-chave.md](rotacao-de-chave.md) · [backup-e-dr.md](backup-e-dr.md).

> **Honestidade sobre o estado:** os comandos abaixo foram escritos a partir do código e do ambiente de desenvolvimento. Os marcados com ⚠️ **não foram executados** em um ambiente real; ensaie-os em hml antes de depender deles numa madrugada de incidente. Troque `docker exec pix-…` pelos equivalentes da sua plataforma (kubectl, psql remoto etc.).
