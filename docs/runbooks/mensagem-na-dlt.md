# Mensagem na DLT / lag de consumer (`PixDltMessages`, `PixConsumerLagHigh`, `PixConsumerStuck`)

**Sintoma:** `increase(pix_dlt_messages_total[5m]) > 0` (rótulo `topic` = tópico de origem); ou lag crescente / consumer sem avançar.
**Impacto:** uma mensagem do SPI (`payment-settled`, `payment-status`, `refund-status`) **não foi aplicada**: Pix recebido não creditado, pagamento sem liquidação ou devolução sem resultado.

## Por que foi para a DLT
- **Negócio / poison pill** (payload ilegível, `endToEndId` inválido, status desconhecido): vai **direto** à DLT, sem retry — o consumer segue vivo.
- **Falha técnica** (banco/dependência): retry com backoff (5 s, ×6, até 180 s, 4 tentativas) pelos tópicos `…-retry-0/1/2`; esgotado ⇒ DLT.

## Diagnóstico
```bash
# o que está na DLT (bytes Avro: use o schema registry para ler)
docker exec pix-schema-registry kafka-avro-console-consumer --bootstrap-server kafka:19092 --topic pix.spi.payment-status.v1.dlt \
  --from-beginning --max-messages 5 --property schema.registry.url=http://localhost:8081
# lag por grupo
docker exec pix-kafka kafka-consumer-groups --bootstrap-server localhost:19092 --describe --group ms-pix-manager.payment-status
```
Logs: `docker logs pix-app | grep "Mensagem na DLT"` (traz topic/partition/offset/key). Procure o erro anterior com o mesmo `endToEndId`/`traceId`.

## Ação
1. Classifique: dado inválido do produtor (corrija **no spi-gateway**) ou falha nossa (corrija o app).
2. **Reprocesso é manual** e seguro: reenviar a mesma mensagem ao tópico de origem preserva o `messageId`, e o **inbox** decide se já foi aplicada. ⚠️ Não existe ferramenta pronta no repositório para o replay (copiar os bytes da DLT de volta ao tópico original, p.ex. com `kcat -C … | kcat -P …`); trate como pendência e ensaie em hml.
3. Consumer parado com lag (`PixConsumerStuck`): reinicie o app se não houver erro; verifique `max.poll.interval` e deadlocks no banco.

## Escalonamento
Qualquer mensagem de **Pix recebido** (`pix.spi.payment-settled.v1`) na DLT em produção: tratar como incidente (cliente pode ter recebido sem crédito).
