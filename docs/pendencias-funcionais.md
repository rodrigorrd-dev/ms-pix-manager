# Pendências funcionais — o que existe, o que falta e como entregar

Escrito depois das rodadas de carga, caos, segurança e funcionalidades de 2026-10-16. Honesto sobre o estado: **o que está marcado “implementado” tem teste e foi exercitado no ambiente local; o que está marcado “não implementado” não existe na API.**

| Capacidade | Estado | Evidência / o que falta |
|---|---|---|
| Prova de posse (OTP) de chave e-mail/telefone | ✅ **Implementado** | `OwnershipProofServiceTest` (10), `PixKeyOwnershipProofIT` (4), contrato do gateway (13), `scripts/demo-flow.py`. Falta: serviço de notificação **real** (o contrato foi definido aqui) e revisar os parâmetros com segurança |
| Webhook de Pix recebido (`/webhook`, entrega assinada, retry, DEAD, reenvio) | ✅ **Implementado** | `WebhookServicesTest` (12), `WebhookUrlPolicyAdapterTest` (SSRF), sender contra WireMock/HTTPS, `WebhookApiIT` (7), roteiro com receptor HTTPS. Falta: `GET /v1/webhook-deliveries` (listar), envio paralelo, mTLS com receptor, webhook de **cobrança/recorrência** (`webhookrec`/`webhookcobr`) |
| Pagar **QR dinâmico** | ⬜ **Não implementado** (responde 422 `PIX-422-000`) | Gerar/consultar a cobrança funciona. Falta: buscar o payload JWS na URL do recebedor (SSRF, TLS, timeout), validar assinatura/cadeia (JWKS do PSP recebedor) e CRC, tratar vencimento/valor, e ligar ao fluxo de pagamento por `QR_DYNAMIC` |
| **Agendamento** (Pix agendado/recorrente) | ⬜ **Não implementado** | Existem só o domínio (`ScheduledOccurrence`, `ScheduleRetryPolicy`) e o DDL. Falta: API de criar/cancelar, job que dispara na data (0h–8h) e reprocessa 18h–21h, avisos ao cliente, datas 29–31 ⇒ próximo dia útil |
| **Pix Automático** (autorização, cobrança, tentativas) | ⬜ **Não implementado** | Só DDL. Falta: API `rec`/`solicrec`/`cobr`, estados `AGND/NTAG/RIFL`, regras de rejeição, integração com o SPI/DICT correspondente. **Depende de confirmar a obrigatoriedade e o calendário no regulamento** |
| **MED** (devolução especial) | ⬜ **Não implementado** | Só DDL (`med_case`, `med_hop`, …). Falta: abertura/análise/contestação, rastreamento em camadas, bloqueio e devolução por saldo, prazos (80 d / 7 d por etapa / 72 h; ver `docs/regulatory-verification.md`) e os tópicos Kafka de MED |

## Por que isto não foi entregue junto
Cada item acima é uma funcionalidade de produto com **regra regulatória própria, contrato com sistema externo e testes de integração**. Pix Automático e MED, em particular, **não devem ser implementados a partir de suposições**: os prazos e estados precisam ser conferidos no texto vigente do Regulamento/Manuais (os valores atuais estão em `docs/regulatory-verification.md`, com 🟡/❌ em parte deles). Entregar versões parciais dessas regras seria pior do que não entregar.

## Ordem sugerida (valor × risco)
1. **QR dinâmico (pagar)** — completa o fluxo de pagamento que hoje só aceita chave/manual/QR estático. Risco: segurança (SSRF e validação de assinatura).
2. **Agendamento** — domínio pronto; falta orquestração e API. Risco baixo/médio.
3. **MED** — alto impacto regulatório; começar por levantar o texto vigente e os contratos com o spi-gateway.
4. **Pix Automático** — maior; só depois de confirmar escopo e obrigatoriedade para a instituição.

Para cada um: escrever antes um RFC curto (skill `rfc-writing`: objetivo, baseline, gap, ordem, definição de pronto), fechar o contrato com o gateway correspondente e seguir o padrão usado em OTP/webhook (porta de saída + adaptador + teste de contrato + IT + roteiro de demonstração + runbook + alerta).

## Decisões que só você pode tomar
- Quais dessas capacidades são **obrigatórias** para a sua instituição e em qual data.
- Se o serviço de notificação do OTP e o dict-gateway/core/spi-gateway reais já existem (e os contratos).
- Parâmetros de risco: limites padrão por dia, política de OTP, tempo de retenção da outbox.
