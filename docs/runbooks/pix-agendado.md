# Runbook — Pix agendado não executa (`PixScheduledOccurrencesOverdue`)

**Impacto:** o cliente agendou um pagamento e ele **não saiu na data**. Gravidade alta: é uma promessa não cumprida.

## Diagnóstico
1. O worker roda? `ScheduledPaymentJob` (ShedLock `scheduled-payments`, a cada 30 s). Procure no log por `Pix agendado:` ou por `Erro ao processar a ocorrência`.
   ```sql
   SELECT lockname, locked_at, lock_until, locked_by FROM shedlock WHERE name = 'scheduled-payments';   -- lock_until no futuro há muito tempo = instância morta segurando o lock
   ```
2. O que está parado?
   ```sql
   SELECT o.id, o.due_date, o.status, o.attempt_count, o.next_attempt_at, o.last_error_code, o.payment_order_id
     FROM scheduled_payment_occurrence o
    WHERE o.status IN ('SCHEDULED','RETRY_PENDING','PROCESSING') AND o.due_date < current_date ORDER BY o.due_date;
   ```
   - `SCHEDULED` com `next_attempt_at` no futuro e `last_error_code` `PIX-503-001`/`PIX-502-001`: **DICT fora** (falha técnica, backoff de 2 min × tentativa, até 5 vezes).
   - `PROCESSING` sem `payment_order_id`: instância caiu entre reivindicar e criar a ordem; o worker devolve ao fluxo após `stuck-after` (10 min).
   - `PROCESSING` com ordem: a ordem não anda — siga `pagamento-preso.md`.
3. Agendamento `CANCELLED` não executa (esperado).

## Ação
- DICT/core fora: restabeleça a dependência; o worker retoma sozinho.
- Atraso acima de `pix.schedule.max-late-days` (3): a ocorrência vira `FAILED(MISSED)` **de propósito** (não paga fora de hora). Decisão de negócio: avisar o cliente e, se for o caso, criar um pagamento imediato.
- Lock preso: apague a linha em `shedlock` **somente** se a instância dona não existe mais.

## Prevenção
Monitore `pix_scheduled_occurrences_overdue` e o uptime do worker; mantenha ≥2 réplicas (o `SKIP LOCKED` impede duplicidade).
