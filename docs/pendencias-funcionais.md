# Pendências funcionais — o que existe, o que falta e como entregar

Estado em 2026-10-16. **O que está “implementado” tem teste de integração e foi exercitado no ambiente local (docker compose) contra mocks.** Isso **não** é o mesmo que testado contra os sistemas reais (DICT, core, SPI, notificação): os contratos externos são rascunhos definidos neste projeto.

| Capacidade | Estado | Evidência | O que ainda falta |
|---|---|---|---|
| Prova de posse (OTP) de chave e-mail/telefone | ✅ | `OwnershipProofServiceTest`, `PixKeyOwnershipProofIT`, contrato do gateway | Serviço de notificação **real** |
| Webhook de Pix recebido | ✅ | `WebhookServicesTest`, `WebhookApiIT`, roteiro com receptor HTTPS | Listar entregas (`GET /v1/webhook-deliveries`), envio paralelo, mTLS, webhooks de cobrança/recorrência |
| **Pagar QR dinâmico** | ✅ | `HttpDynamicQrPayloadAdapterTest` (9), `PaymentOrderApiIT`, roteiro (payload assinado pelo mock) | Trust store ICP-Brasil **de produção**; verificação de revogação (CRL/OCSP); teste contra PSP real |
| **Pix Agendado** | ✅ | `ScheduledPaymentTest`, `ScheduledPaymentApiIT` (6: criação, cancelamento, execução, retentativa 18–21h com aviso, falha de negócio/técnica, atraso) | Execução na data real não foi observada ao vivo (os ITs controlam o relógio); notificação ao cliente depende de consumidor de `pix.schedule.v1`; janela 0h–8h não imposta |
| **MED** (REPORTER e REPORTED) | ✅ parcial | `MedCaseApiIT` (4), roteiro (abre → aceita → bloqueia → devolve → fecha) | Entrada por Kafka da notificação (`pix.spi.med-notification.v1`, sem schema Avro ainda); **rastreamento automático em camadas** (`med_hop`); bloqueio parcial conforme entra saldo; gateway DICT real; prazos de 72 h/7 d por etapa |
| **Pix Automático** (pagador) | ✅ parcial | `AutomaticPixApiIT` (5: autorização, cobrança, execução, retry com/sem `allow_retry`, expiração), roteiro | Entrada por Kafka da solicitação/cobrança do SPI; **prazos não confirmados no regulamento** (`docs/regulatory-verification.md`); gateway de resposta real; eventos de cobrança/tentativa (só a autorização publica em `pix.automatic.v1`); recebedor (PJ) fora do escopo |
| Reconciliação | ⬜ | só DDL/contratos | tudo |

## Decisões que só você pode tomar
- **Compliance:** confirmar no texto vigente os prazos do Pix Automático (antecedência da cobrança, nova tentativa, validade da solicitação) e as etapas/prazos do MED; decidir sobre revogação de certificado no QR dinâmico.
- **Integração:** formalizar os contratos do dict-gateway (MED), do spi-gateway (Pix Automático, entrada de solicitações) e do serviço de notificação; hoje são rascunhos.
- **Produto:** quais capacidades são obrigatórias para a instituição e a partir de quando; parâmetros de risco (limites padrão, OTP, retenção da outbox, horizonte de agendamento).

## Próximos passos sugeridos
1. Schemas Avro + consumers (`pix.spi.med-notification.v1`, `pix.spi.automatic-request.v1`, `pix.spi.automatic-charge.v1`) reaproveitando os casos de uso de entrada que já existem na API.
2. Rastreamento em camadas do MED (`med_hop`), depois de fechado o contrato com o gateway.
3. Rodar contra os sistemas reais em homologação e repetir carga/caos com os novos workers (os testes de carga atuais não os cobrem).
