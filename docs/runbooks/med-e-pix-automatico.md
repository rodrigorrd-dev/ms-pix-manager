# Runbook — MED parado (`PixMedCasesOverdue`) e Pix Automático sem débito (`PixAutomaticAttemptsOverdue`)

## MED — caso REPORTED sem análise após 7 dias
**Impacto:** prazo regulatório de análise do PSP do recebedor estourado; risco de sanção e de o dinheiro sair da conta.
```sql
SELECT id, end_to_end_id, status, reported_at, analysis_deadline_at, blocked_amount FROM med_case
 WHERE role = 'REPORTED' AND status = 'OPEN' AND analysis_deadline_at < now() ORDER BY analysis_deadline_at;
```
1. Decida cada caso com a operação: `POST /v1/med-cases/{id}/acceptance` (bloqueia o valor) ou `/refusal`.
2. Bloqueio recusado pelo core (sem saldo): o caso fica `ACCEPTED` com `blocked_amount = 0`; veja `SELECT note FROM med_case_history WHERE case_id = ... ORDER BY occurred_at DESC`. Não há bloqueio parcial automático: decisão manual.
3. `REFUNDING` parado: veja a devolução (`pix_refund.status`). `NAO_REALIZADO` devolve o caso a `ACCEPTED` (o bloqueio já foi liberado para devolver — **o saldo pode ter saído**; avalie novo bloqueio/ação junto ao jurídico).

## Pix Automático — tentativa vencida e não debitada
```sql
SELECT t.id, t.attempt_type, t.status, t.settlement_date, t.last_error_code, c.txid, c.status AS charge
  FROM pix_automatic_attempt t JOIN pix_automatic_charge c ON c.id = t.charge_id
 WHERE t.status IN ('AGENDADA','SOLICITADA') AND t.settlement_date < current_date ORDER BY t.settlement_date;
```
- `AGENDADA` com `last_error_code` `PIX-503-001`/`PIX-502-001`: DICT/dependência fora (o worker retenta a cada ciclo; espera 1 min após falha).
- `SOLICITADA` sem ordem: instância caiu após reivindicar; volta ao fluxo após `stuck-after` (10 min).
- `SOLICITADA` com ordem: siga `pagamento-preso.md`.
- Atraso acima de `pix.automatic.max-late-days` (1): a tentativa vira `EXPIRADA` e a cobrança `EXPIRADA` **de propósito** (não debita fora de hora). Avise o recebedor pelos canais combinados.
- Autorização `CANCELADA`/teto reduzido: a tentativa é recusada (`CANCELADA`/`REJEITADA`) — esperado.
- Lock preso: `SELECT * FROM shedlock WHERE name = 'automatic-pix'`; apague **só** se a instância dona não existe mais.
