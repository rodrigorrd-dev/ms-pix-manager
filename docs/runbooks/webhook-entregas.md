# Entregas de webhook (`PixWebhookDeliveriesDead`, `PixWebhookDeliveriesOverdue`)

**Sintoma:** `pix_webhook_deliveries_dead > 0` (entregas que esgotaram as tentativas) ou `pix_webhook_deliveries_overdue > 0` (entregas vencidas há mais de 5 min e ainda não enviadas).
**Impacto:** o cliente (recebedor) **não é avisado** dos Pix recebidos. O dinheiro está creditado e o Pix existe (`GET /api/pix/v2/pix/{e2eid}`); só a notificação falhou. Nada aqui afeta saldo.

## Como funciona (para entender o estado)
Pix recebido numa chave com webhook ⇒ linha `PENDING` em `webhook_delivery` (na mesma transação do Pix) ⇒ o `WebhookDeliveryJob` (a cada 2 s, lote de 20) faz `POST <webhookUrl>/pix` assinado ⇒ 2xx = `DELIVERED`; qualquer outra resposta, timeout, DNS ou URL bloqueada = `RETRYING` com backoff (5 s, 30 s, 3 min, 18 min, 1h48, 6 h…) ⇒ após 8 tentativas = `DEAD`. Só 2xx é sucesso (um 3xx **não** é seguido).

## Diagnóstico
```sql
-- o que está parado e por quê
select d.id, d.status, d.attempts, d.last_http_status, d.last_error, d.next_attempt_at, s.url
  from webhook_delivery d join webhook_subscription s on s.id = d.subscription_id
 where d.status in ('DEAD','RETRYING','PENDING') order by d.updated_at desc limit 50;
-- resumo
select status, count(*) from webhook_delivery group by status;
```
Leia `last_http_status` e `last_error`: `503/500` ⇒ receptor com problema; `401/403` ⇒ o receptor rejeita nossa assinatura (confira `x-pix-signature`/relógio); `404` ⇒ URL errada (o app chama `<webhookUrl>/pix`); `ConnectException/Timeout` ⇒ rede ou receptor fora; **"URL bloqueada pela política"** ⇒ o host passou a resolver para endereço interno (anti-SSRF) ou perdeu o HTTPS — **trate como possível ataque** e confirme com o cliente antes de liberar.

`overdue` com `DEAD`=0: o job não está rodando (ShedLock `webhook-dispatch` preso? app fora?) ou o lote é pequeno para o volume — aumente `PIX_WEBHOOK_DISPATCH_BATCH` (sequencial: um receptor lento atrasa os demais; o timeout é 5 s).

## Ação
1. Avise o cliente (a URL e o erro estão na linha). Ele corrige o receptor ou atualiza a URL: `PUT /api/pix/v2/webhook/{chave}`.
2. Entregas `RETRYING` se resolvem sozinhas quando o receptor volta. As `DEAD` **não**: reenvie uma a uma, com token de back-office (escopo `webhook.admin`, acesso a todas as contas), `POST /v1/webhook-deliveries/{id}/resend` (volta a `PENDING`, tentativas zeradas). Em lote (⚠️ SQL manual, não testado em produção): `update webhook_delivery set status='PENDING', attempts=0, next_attempt_at=now() where status='DEAD' and subscription_id = '<id>';`
3. O receptor deve ser **idempotente** (pode receber o mesmo `endToEndId` mais de uma vez: reenvio, retry após timeout em que ele já tinha processado) e validar `x-pix-signature` = `v1=HMAC-SHA256(chave, x-pix-timestamp + "." + corpo)` rejeitando timestamp com mais de 5 min.

## Escalonamento
`DEAD` crescendo para vários clientes ao mesmo tempo ⇒ problema nosso (rede de saída, DNS, certificado do receptor, assinatura): plataforma + dono do serviço.
