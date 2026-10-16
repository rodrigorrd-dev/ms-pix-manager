# 11 — Glossário e convenções de nomenclatura (inglês)

> Regra da skill `architecture-conventions`: **identificadores em inglês**; quando o termo é definido por regulação, contrato ou sistema externo, **fica literal** — traduz-se a implementação, não a linguagem ubíqua. Decisão do solicitante (2026-10-15): **tudo em inglês** no projeto (módulos, pacotes, classes, métodos, tabelas, colunas, campos JSON próprios, tópicos, métricas, IDs de requisito e nomes de arquivo). A **prosa** da documentação segue em pt-BR.

## 1. Termos regulatórios que ficam **literais** (não traduzir)
| Termo | Origem | Observação |
|---|---|---|
| `cpf`, `cnpj`, `Cpf`, `Cnpj` | Receita Federal | VOs literais; o conceito genérico é `Document` (`Cpf \| Cnpj`) — nunca `taxId` |
| `txid`, `endToEndId` (`e2eId`), `rtrId`, `ispb`, `idRec` | BCB / SPI | em Java: `TxId`, `EndToEndId`, `Ispb`; colunas `txid`, `end_to_end_id`, `rtr_id`, `ispb`, `id_rec` |
| `PIX`, `DICT`, `SPI`, `RSFN`, `MED`, `BR Code`, `pacs.008/002/004`, `camt.*` | BCB / ISO 20022 | |
| `cob`, `cobv`, `loc`, `webhook` | API Pix | só na **superfície compatível com o BCB** (rotas); internamente: `Charge` (`IMMEDIATE`/`DUE`), `PixLocation` |
| Valores de enum **persistidos/de fio do BCB** | API Pix 2.10.0 | `ATIVA`, `CONCLUIDA`, `REMOVIDA_PELO_USUARIO_RECEBEDOR`, `REMOVIDA_PELO_PSP`, `EM_PROCESSAMENTO`, `DEVOLVIDO`, `NAO_REALIZADO`, `CRIADA`, `APROVADA`, `REJEITADA`, `EXPIRADA`, `CANCELADA`, `AGND`/`NTAG`/`RIFL`, `AP13..15`, `SEMANAL…ANUAL`, `ORIGINAL`, `RETIRADA`, `MED_*` — mantidos **literalmente** (renomear quebraria o contrato) |
| Campos JSON da API Pix | API Pix 2.10.0 | `calendario`, `valor.original`, `devedor`, `chave`, `solicitacaoPagador`, `infoAdicionais`, `devolucao`… — **literais na superfície `/api/pix/v2`** |
| Escopos OAuth do BCB | API Pix | `cob.read`, `cob.write`, `pix.read`, `webhook.write`, `payloadlocation.*` |

**Decisão de fio (wire) — registrada aqui, conforme a skill:**
- **`/api/pix/v2` (compat BCB):** corpo e rotas **em português, idênticos ao OpenAPI**; os DTOs Java têm campos **em inglês** mapeados com `@JsonProperty("expiracao")` etc. (o idioma do fio não vaza para o código).
- **`/v1` (gestão) e Problem Details:** **inglês** — `code`, `errors`, `timestamp`, `traceId`, `document`, `amount`, …
- **Enums próprios** (status de ordem, claim, MED…): constantes em **inglês** (`SETTLED`, `RETRY_PENDING`, `OPEN`…), persistidas como `varchar` + `CHECK`.

## 2. Mapa dos módulos (pt → en)
| Antes (pt) | Agora (en) | Contexto |
|---|---|---|
| `chave` | `pixkey` | chaves Pix / DICT / reivindicação |
| `cobranca` | `charge` | `cob`/`cobv`/`loc` |
| `recebimento` | `receivedpix` | Pix recebido |
| `devolucao` | `refund` | devolução / natureza MED |
| `conciliacao` | `reconciliation` | conciliação |
| `pagamento` | `payment` | ordens de pagamento |
| `agendamento` | `scheduling` | Pix Agendado |
| `pixautomatico` | `automaticpix` | Pix Automático (lado pagador) |
| `limite` | `limit` | limites |
| `med` | `med` | MED 2.0 (termo regulatório) |
| `webhook` | `webhook` | |
| `plataforma` | `platform` | transversal (`outbox`, `inbox`, `idempotency`, `audit`, `security`, `observability`, `error`, `config`, `document`) |

## 3. Vocabulário canônico (não criar sinônimos)
`Charge` (cobrança) · `ReceivedPix` · `Refund` (devolução) · `PaymentOrder` · `ScheduledPayment` / `ScheduledPaymentOccurrence` · `AutomaticAuthorization` / `AutomaticCharge` / `AutomaticAttempt` · `PixKey` · `KeyClaim` · `MedCase` / `MedHop` / `MedContestation` · `WebhookSubscription` / `WebhookDelivery` · `LedgerReservation` · `Limit` / `LimitUsage` · `Document` (CPF/CNPJ) · `Holder` (titular) · `Account` · `Receiver` (recebedor) · `Payer` (pagador).
**Colisão evitada:** `Document` = CPF/CNPJ neste ms (não há anexos). Se anexos surgirem, usar `Attachment`.

## 4. Sufixos por papel (da skill)
| Papel | Sufixo | Exemplo |
|---|---|---|
| Porta de entrada | `UseCase` | `CreateChargeUseCase` |
| Implementação (sem `Impl`, sem `@Service`) | `Service` | `CreateChargeService` |
| Colaborador interno | `Service` | `ChargeNumberingService` |
| Catálogo de regra | `Policy` | `RefundPolicy`, `LimitPolicy` |
| Aplicador que lança | `Guard` | `OwnershipGuard` |
| Porta de saída | `PortOut` | `ChargePersistencePortOut`, `ChargeQueryPortOut`, `DictGatewayPortOut`, `LedgerPortOut` |
| Adaptador de saída | `Adapter` | `ChargePersistenceAdapter`, `DictGatewayAdapter`, `LedgerAdapter` |
| Entidade JPA / mapper | `Entity` / `EntityMapper` | `ChargeEntity`, `ChargeEntityMapper` |
| Mapper de borda REST | `Mapper` | `ChargeMapper` |
| Repositório Spring Data | `Repository` | `ChargeRepository` |
| Conversor JPA | `Converter` | `DocumentCryptoConverter` |
| Projeção de leitura | `Projection` | `ReconciliationDailyProjection` |
| Corpo HTTP | `Request` / `Response` (sem infixo `Record`) | `CreateChargeRequest` |
| Evento de domínio / listener | `Event` / `Listener` | `ChargeCreatedEvent` |
| Consumer Kafka / Job | `Consumer` / `Job` | `ReceivePixConsumer`, `ClaimExpirationJob` |
| Resultado sem exceção | `sealed interface …Result` | `CreateChargeResult` |
| Controller | `Controller` / `ControllerImpl` | `ChargeController` / `ChargeControllerImpl` |
| Suporte técnico reutilizável | `Support` | `BackoffSupport` |
| Testes | `Test` / `WebTest` / `IT` / `ContractTest` / `ConcurrencyTest` | `CreateChargeServiceTest`, `ChargeControllerImplWebTest`, `ChargePersistenceAdapterIT` |
Verbos: `findBy…`/`existsBy…` (leitura), `save`/`saveAll`, `create`/`of` (fábricas), `with<Field>` (wither imutável), `is…`/`has…`/`can…`, `markPaid`, `requestRefund`, `confirm`/`cancel`.

## 5. Banco, mensageria, observabilidade e requisitos
| Item | Regra | Exemplo |
|---|---|---|
| Tabelas/colunas | `snake_case` inglês; termos regulatórios literais | `pix_charge`, `end_to_end_id`, `document_hash` |
| Enums persistidos | `varchar` + `CHECK`; inglês, exceto literais do BCB (§1) | `SETTLED`, `ATIVA` |
| Tópicos Kafka | `pix.<domain>.<fact>.v<major>`, entradas `pix.spi.*`/`pix.dict.*` | `pix.received.v1` |
| Avro | namespace `br.com.pixmanager.events.v1`; campos `camelCase` inglês | `PixReceivedEvent` |
| Métricas | `pix_<domain>_<what>_total` | `pix_refunds_total{status}` |
| Config YAML | `pix.<area>.<key>` | `pix.institution.ispb`, `pix.policy.max-keys-per-account.pf` |
| Códigos de erro | **somente** via `ErrorCodes` | `PIX-422-001` |
| IDs de requisito | `FR-*` (funcional), `BR-*` (regra de negócio), `NFR-*` (não funcional), `ADR-nnn` | `BR-REF-03` |
| Arquivos de doc | inglês, prefixo numérico | `02-requirements-business-rules.md` |
| Endpoints de gestão | plural, `kebab-case`; sub-recurso substantivo para transição | `POST /v1/payment-orders/{id}/cancellation` |

## 6. Onde cada coisa vai (decisão rápida)
```
Regra de negócio sem I/O?        → <module>/domain (dados+invariantes) ou <module>/application/service (orquestração)
Fala com banco/Kafka/HTTP?       → porta em application/port/out (…PortOut), implementação em adapter/out/** (…Adapter)
Trata uma requisição HTTP?       → adapter/in/rest (…Controller + …ControllerImpl → chama um UseCase)
Reage a Kafka/job?               → adapter/in/messaging (…Consumer) | adapter/in/job (…Job) → chama um UseCase
Código de erro?                  → ErrorCodes primeiro, depois a constante
Termo regulatório (CPF, txid…)?  → literal, independentemente do idioma padrão
```

## 7. O que a skill apontou como armadilha — e como evitamos
| Armadilha (FINDING da skill) | Prevenção aqui |
|---|---|
| Adapter que foge do padrão de sufixo (`AuditAdapter`) | ArchUnit: `*PortOut` ↔ `*Adapter` 1:1 pelo prefixo |
| Pacote com nome impreciso (`port/in/persistence`) | portas de entrada em `application/port/in`, sem subpasta enganosa |
| Javadoc/doc com rota/parâmetro que não existe | spec e código no mesmo commit; `OpenApiContractTest` |
| Literais de erro fora do catálogo | teste `grep` no CI (`"PIX-` fora de `ErrorCodes`) |
| Fio misturando idiomas | decisão de fio registrada em §1 antes de existir cliente |
| Simple name igual ao do Spring (`HttpStatus`) | proibido reutilizar nomes de tipos do JDK/Spring (`HttpStatus`, `Optional`…) |
