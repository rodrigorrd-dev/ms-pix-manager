# Dependência indisponível (`PixCircuitBreakerOpen`, `PixKeysStuckPendingDict`, `PixLedgerReservationsStuck`)

**Sintoma:** `resilience4j_circuitbreaker_state{name="core-ledger"|"dict-gateway",state="open"} == 1`; chaves em `PENDING_DICT` ou reservas `PENDING` há mais de 5 min.
**Impacto:** `dict-gateway` aberto ⇒ chaves não ativam e pagamento por chave/QR falha em consultar o DICT (502/503). `core-ledger` aberto ⇒ pagamentos ficam `VALIDATED` (sem reservar saldo); **nenhum dinheiro é movimentado sem reserva** (falha segura).

## Diagnóstico
1. Qual dependência: o rótulo `name` do alerta.
2. A dependência responde? `curl -s -o /dev/null -w "%{http_code}\n" $CORE_LEDGER_URL/…` (peça o healthcheck ao time do core/dict-gateway). No dev: `curl localhost:18090/health`.
3. Erros no app: `docker logs pix-app | grep -E "DICT indisponível|Core respondeu|esgotou .* tentativas"`.
4. Quanto trabalho acumulou: no dashboard, "Reservas de saldo presas" e "Chaves presas em PENDING_DICT".

## Ação
- O app **recupera sozinho**: o breaker passa a half-open após 30 s e os jobs (`PendingReservationJob` a cada 2 s, `PendingDictKeysJob` a cada 3 s) retomam o trabalho pendente quando a dependência volta. Não reinicie o app por isso.
- Reserva que **esgotou 30 tentativas técnicas** é marcada `FAILED` (o log diz); o pagamento correspondente vira `REJECTED`/`FAILED_TECHNICAL`. Se a causa foi uma indisponibilidade longa, avalie reprocessar com o negócio (nova ordem; a antiga não é reaberta).
- Recusa **de negócio** (`INSUFFICIENT_FUNDS`, `ACCOUNT_BLOCKED`) **não** abre o circuito e não é retentada: é resposta definitiva.

## Escalonamento
Indisponibilidade > 15 min ou perda de reservas: dono do core / dict-gateway e gestão de incidentes (há dinheiro e experiência do cliente envolvidos).
