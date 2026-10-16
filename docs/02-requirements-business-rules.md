# 02 — Requisitos, regras de negócio e cenários (Specification-Driven)

> Atualizado em 2026-10-15 com as respostas da rodada 1 (`10`). IDs são **estáveis** e referenciados por testes (`@DisplayName("BR-KEY-02 …")`), commits e tabela de rastreabilidade (§8). Valores marcados **[R]** vêm do Regulamento e estão parametrizados em `PixPolicyProperties` (ver `09` §4 e `10`).

## 1. Atores e glossário

| Termo | Definição (ubiquitous language) |
|---|---|
| **PSP** | Participante do Pix (a própria instituição, `ispb`). |
| **Recebedor / Pagador** | Usuário final que recebe / paga Pix. |
| **Chave Pix (`pixKey`)** | Apelido no DICT: `CPF`, `CNPJ`, `PHONE`, `EMAIL`, `EVP`. |
| **Cobrança imediata (`cob`)** | Cobrança com expiração em segundos. |
| **Cobrança com vencimento (`cobv`)** | Cobrança com data de vencimento, multa, juros, desconto, abatimento. |
| **Location (`loc`)** | URL do payload do QR dinâmico. |
| **`txid`** | Identificador de conciliação do recebedor. |
| **`endToEndId` (e2e)** | Identificador único da transação no SPI (32). |
| **Devolução** | Retorno parcial/total de um Pix recebido (pacs.004). |
| **MED** | Mecanismo Especial de Devolução (fraude/falha operacional). |
| **Document (`document`)** | CPF/CNPJ do titular/devedor/pagador. `Cpf` e `Cnpj` são termos regulatórios mantidos literais; `Document` é o VO selado `Cpf \| Cnpj` (este ms não tem anexos, então não há colisão com "arquivo"). |

Atores: Cliente PF/PJ, Sistema parceiro (OAuth `client_credentials`), Operador de back-office, Analista MED, `spi-gateway`, DICT/BCB, Core bancário (ledger).

## 2. Requisitos funcionais

### 2.1 Documento (CPF) — transversal
| ID | Requisito |
|---|---|
| FR-DOC-01 | O sistema MUST validar todo CPF informado (titular, devedor, pagador, chave `CPF`) com **11 dígitos + dígitos verificadores módulo 11**, rejeitando sequências de dígitos repetidos (`000.000.000-00` … `999.999.999-99`). |
| FR-DOC-02 | Entrada MAY conter máscara (`123.456.789-09`); o sistema MUST normalizar para 11 dígitos antes de validar/gravar. |
| FR-DOC-03 | CNPJ MUST ser aceito **numérico e alfanumérico** (formato da Receita Federal: 12 posições `[0-9A-Z]` + 2 dígitos verificadores numéricos = `^[0-9A-Z]{12}[0-9]{2}$`; a API Pix aceita `^[0-9A-Z]{14}$`); entrada em minúsculas é normalizada para maiúsculas; **o dígito verificador é sempre validado** (módulo 11 com valor do caractere = código ASCII − 48). Os dois formatos coexistem por tempo indeterminado. |
| FR-DOC-04 | CPF/CNPJ MUST ser armazenado **criptografado** (AES-256-GCM) com **índice cego HMAC-SHA256** para busca; MUST ser retornado **mascarado** (`***.456.789-**`) por padrão e **completo apenas** a cliente OAuth com o escopo `pii.document.read` (cada acesso completo grava `audit_log`). |

### 2.2 Chaves Pix (DICT)
| ID | Requisito |
|---|---|
| FR-KEY-01 | Registrar chave de tipo CPF/CNPJ/PHONE/EMAIL/EVP vinculada a uma conta. |
| FR-KEY-02 | Consultar chave (própria e de terceiros via DICT, com estatísticas opcionais). |
| FR-KEY-03 | Atualizar dados de conta vinculada à chave; excluir chave (motivo obrigatório). |
| FR-KEY-04 | Reivindicação de **posse** e **portabilidade** nos dois papéis (reivindicador e doador): criar, reconhecer, confirmar, cancelar, completar. |
| FR-KEY-05 | Verificar existência de chaves em lote (`keys/check`). |
| FR-KEY-06 | Reconciliar periodicamente chaves locais × DICT (CID) e alertar divergência. |
### 2.3 Recebedor
| ID | Requisito |
|---|---|
| FR-COB-01 | Criar cobrança imediata (`POST /cob` com `txid` gerado, `PUT /cob/{txid}` com `txid` do cliente), consultar, revisar (PATCH), listar com filtros e paginação. |
| FR-COB-02 | Criar/revisar/consultar/listar cobrança com vencimento. |
| FR-COB-03 | Gerar `loc`, consultar, desvincular `txid` da location. |
| FR-COB-04 | Expor payload público JWS (`/{pixUrlAccessToken}`) para QR dinâmico. |
| FR-PIX-01 | Registrar Pix recebido (origem: evento SPI), conciliar com cobrança por `txid`, consultar por `e2eId` e listar por período/`txid`/CPF. |
| FR-REF-01 | Solicitar devolução (`PUT /pix/{e2eid}/devolucao/{id}`), consultar, acompanhar status. |
| FR-WHK-01 | Cadastrar/consultar/excluir webhook por chave; entregar notificações de Pix recebido e devolução com reentrega. |
### 2.4 Pagador
| ID | Requisito |
|---|---|
| FR-PAY-01 | Criar ordem de pagamento por chave, dados manuais, QR estático ou QR dinâmico. |
| FR-PAY-02 | Resolver chave no DICT, validar CPF/CNPJ do favorecido, aplicar limites, reservar saldo (ledger), enviar ao SPI. |
| FR-PAY-03 | Acompanhar status (pacs.002), liquidar/estornar reserva, notificar o cliente. |
| FR-LIM-01 | Gerenciar limites (por transação, diário, noturno, novo favorecido). |
### 2.4b Agendado, Pix Automático e limites
| ID | Requisito |
|---|---|
| FR-SCH-01 | Agendar Pix único/recorrente, listar, consultar, cancelar; executar na data e notificar falhas. |
| FR-AUT-01 | Receber, listar, aceitar/rejeitar autorizações de Pix Automático; ajustar limites e cancelar; executar cobranças recorrentes e acompanhar tentativas. |
| FR-LIM-02 | Alterar limite com carência de aumento (24–48 h) e redução imediata; limites de dispositivo não cadastrado. |
| FR-WHK-02 | Reenviar entrega de webhook `DEAD` manualmente. |

### 2.5 MED
| ID | Requisito |
|---|---|
| FR-MED-01 | Registrar notificação de infração (DICT `funds-recoveries/{id}/infraction-reports`), com bloqueio cautelar de valor. |
| FR-MED-02 | Analisar (aceitar/recusar), cancelar, encerrar; solicitar devolução MED e acompanhar. |
| FR-MED-03 | Devolver com natureza `MED_FRAUDE` ou `MED_OPERACIONAL`. |
| FR-MED-04 | **MED 2.0:** rastrear em camadas, bloquear/devolver parcialmente conforme entra saldo (`med_hop`). |
| FR-MED-05 | Contestação pelo recebedor da devolução via MED (80 dias da devolução). |

## 3. Regras de negócio

### 3.1 Documento
| ID | Regra | Prioridade |
|---|---|---|
| BR-DOC-01 | CPF inválido ⇒ `PIX-422-001` (422), mensagem sem eco do valor. | MUST |
| BR-DOC-02 | A validação roda no **VO `Cpf`** (construtor privado + `Cpf.of(String)` retornando `Result`), nunca em `if` espalhado no controller. Bean Validation `@ValidCpf` apenas delega ao VO. | MUST |
| BR-DOC-03 | Logs, eventos e exceções MUST conter apenas o CPF mascarado ou o hash; `toString()` do VO retorna mascarado. | MUST |
| BR-DOC-04 | O mesmo CPF MAY ter várias contas, mas no máximo **uma** chave `CPF` ativa por vez em todo o DICT. | MUST |
| BR-DOC-05 | **Algoritmo CNPJ** (numérico e alfanumérico): `valor(c) = ASCII(c) − 48` (dígitos 0–9; `A`=17 … `Z`=42). DV1 = pesos `5,4,3,2,9,8,7,6,5,4,3,2` sobre os 12 primeiros; DV2 = pesos `6,5,4,3,2,9,8,7,6,5,4,3,2` sobre os 12 + DV1; `resto = soma mod 11`; `DV = resto < 2 ? 0 : 11 − resto`. Rejeita `00000000000000` e sequências de caracteres repetidos. Verificado com `12.ABC.345/01DE-35` (válido) e `11.222.333/0001-81` (válido). | MUST |
| BR-DOC-06 | Titular **PF** usa CPF; **PJ** usa CNPJ. Chave `CPF` só em conta PF, chave `CNPJ` só em conta PJ. | MUST |

### 3.2 Chaves
| ID | Regra |
|---|---|
| BR-KEY-01 | Formatos conforme DICT 2.12.1: `PHONE` E.164 (`+55…`, ≤15 dígitos), `EMAIL` minúsculo ≤77, `EVP` UUID minúsculo gerado pelo DICT, `CNPJ` 14 alfanuméricos. (MUST) |
| BR-KEY-02 | Chave `CPF` MUST ser igual ao CPF do titular da conta; chave `CNPJ` ao CNPJ do titular PJ. Violação ⇒ `PIX-422-010`. |
| BR-KEY-03 | Limite de chaves **por conta** (não por pessoa): PF 5, PJ 20 (Manual Operacional do DICT; `pix.policy.max-keys-per-account.{pf,pj}`). |
| BR-KEY-04 | `PHONE`/`EMAIL` exigem **prova de posse** (código OTP) antes de `ACTIVE`; estados: `PENDING_OWNERSHIP_PROOF → PENDING_DICT → ACTIVE`. **Implementado:** código de 6 dígitos (`SecureRandom`), guardado só como HMAC ligado à chave, comparado em tempo constante; valido por **5 min**, **5 tentativas** por código, espera de **60 s** entre pedidos e teto de **10 códigos/24 h** por chave (`pix.policy.otp.*` — parâmetros **de projeto**, não do regulamento; ajuste com o time de segurança). Entrega por serviço de notificação externo (`pix.otp.sender`); sem canal configurado a prova de posse responde 503 (padrão seguro). |
| BR-KEY-05 | Registro local e no DICT é **consistente eventual**: a chave nasce `PENDING_DICT`; a confirmação do DICT (HTTP síncrono) ou falha leva a `ACTIVE`/`REJECTED`. Chamada ao DICT **fora** da transação de banco (sem HTTP dentro de `@Transactional`). |
| BR-KEY-06 | Exclusão exige motivo ∈ {`USER_REQUESTED`, `ACCOUNT_CLOSURE`, `BRANCH_TRANSFER`, `RECONCILIATION`, `FRAUD`, `DEFAULT_OPERATION`} (valores do DICT; reconferir enum exato em `09`). |
| BR-KEY-07 | Claim: reivindicador cancela o processo se o usuário **não validar a chave até o 30º dia**; doador tem **7 dias** para responder **[R — Manual de Tempos do Pix não verificado; ver `10` E5]**; prazos parametrizados; expiração automática por job com ShedLock. |
| BR-KEY-09 | Toda consulta ao DICT passa pelo **`dict-gateway`** (limite de consultas, cache e retentativas ficam lá); o ms trata `429`/`503` do gateway como falha técnica e **não** implementa token bucket nem assinatura XML do DICT. |
| BR-KEY-08 | Não mexer em chave com claim aberta, exceto pelo fluxo da claim. |

### 3.3 Cobrança
| ID | Regra |
|---|---|
| BR-COB-01 | `txid` `[a-zA-Z0-9]{26,35}`; único por **documento do recebedor** (não global). `POST /cob` gera `txid` de 32 chars. Conflito ⇒ `PIX-409-020`. |
| BR-COB-02 | `valor.original` > 0 e ≤ 9.999.999.999,99 (`\d{1,10}\.\d{2}`). |
| BR-COB-03 | `expiracao` default 86.400 s; mínimo 1 s; expiração calculada de `criacao` (**relógio do servidor**, `Clock`). Após expirar, o pagamento é recusado. |
| BR-COB-04 | A `chave` da cobrança MUST ser `ACTIVE` e pertencer à conta do recebedor autenticado. |
| BR-COB-05 | `devedor` opcional; quando presente exige `cpf` **ou** `cnpj` (nunca ambos) + `nome` ≤ 200. CPF validado (BR-DOC-01). |
| BR-COB-06 | `solicitacaoPagador` ≤ 140; `infoAdicionais` ≤ 50 itens (`nome` ≤ 50, `valor` ≤ 200). |
| BR-COB-07 | Estados: `ATIVA → CONCLUIDA` (pago) · `ATIVA → REMOVIDA_PELO_USUARIO_RECEBEDOR` · `ATIVA → REMOVIDA_PELO_PSP`. Estados finais são imutáveis. Cobrança expirada **continua `ATIVA`** (expiração não é status — doc BCB). |
| BR-COB-08 | Revisão (`PATCH`) só em `ATIVA`; incrementa `revisao`, grava snapshot em `pix_charge_revision`. |
| BR-COB-09 | Cobrança com vencimento: pagável até `dataDeVencimento + validadeAposVencimento` dias (inclusive). Cálculo: `valor = original + multa + juros − abatimento − desconto` (modalidades de multa/juros/desconto conforme API). |
| BR-COB-10 | Uma cobrança só aceita **um** Pix; segundo pagamento ⇒ vira Pix **sem cobrança vinculada** + alerta de conciliação (nunca é rejeitado pelo ms: o SPI já liquidou). |

### 3.4 Pix recebido
| ID | Regra |
|---|---|
| BR-PIX-01 | `endToEndId` é a chave de idempotência do recebimento (único). Reprocessamento ⇒ no-op. |
| BR-PIX-02 | Com `txid` conhecido: valida valor (original+componentes) e muda a cobrança para `CONCLUIDA` **na mesma transação** que grava o Pix e a outbox. |
| BR-PIX-03 | Valor divergente do esperado **não** bloqueia o crédito (SPI já liquidou): registra `reconciliation_status = MISMATCH` e cria alerta. |
| BR-PIX-04 | `horario` = horário de liquidação no SPI (UTC). Exibição em `America/Sao_Paulo`. |

### 3.5 Devolução
| ID | Regra |
|---|---|
| BR-REF-01 | `id` da devolução (`[a-zA-Z0-9]{1,35}`) é idempotente por `(e2eId, id)`; repetição com mesmo corpo ⇒ 200 com o mesmo resultado; corpo diferente ⇒ 422. |
| BR-REF-02 | Status: `EM_PROCESSAMENTO → DEVOLVIDO` ou `NAO_REALIZADO` (final). `rtrId` (32) atribuído ao enviar o pacs.004. |
| BR-REF-03 | Σ devoluções (não `NAO_REALIZADO`) ≤ valor devolvível do Pix (BR-REF-04). Excesso ⇒ `PIX-422-030`. |
| BR-REF-04 | Valor devolvível: Pix comum = valor do Pix; Saque = valor da retirada; Troco = compra (natureza `ORIGINAL`) e troco (`RETIRADA`) separados. |
| BR-REF-05 | Prazo **[R]**: devolução comum até 90 dias do `horario`; MED segue prazo próprio. Fora do prazo ⇒ `PIX-422-031`. |
| BR-REF-06 | Natureza `MED_FRAUDE`/`MED_OPERACIONAL` só por fluxo MED (autorização `med.refund`). |
| BR-REF-07 | A devolução reserva o valor no ledger antes de enviar ao SPI; falha definitiva libera a reserva (compensação). |

### 3.6 Pagamento (pagador)
| ID | Regra |
|---|---|
| BR-PAY-01 | Toda ordem exige `Idempotency-Key` HTTP; chave única por `(client_id, key)`; payload hash armazenado. O **`endToEndId` é gerado na criação** da ordem e é a **chave de idempotência do core/ledger**. |
| BR-PAY-02 | Sequência: validar → resolver favorecido (via dict-gateway) → limites → **`reservar` no core (idempotente por `endToEndId`)** → persistir `RESERVED` + outbox → `spi-gateway` envia pacs.008 → pacs.002: liquidado ⇒ **`confirmar`**; rejeitado/expirado/falha definitiva ⇒ **`cancelar`** (libera). Chamadas ao core **não** ficam dentro da transação do banco: `ledger_reservation` registra a intenção (`PENDING`) e um worker chama o core; sweeper repara estados órfãos. Repetir `reservar/confirmar/cancelar` com o mesmo `endToEndId` é seguro. |
| BR-PAY-03 | Falhas **técnicas** (timeout/5xx) ⇒ retry com backoff+jitter; **negócio** (saldo, limite, chave inexistente, 4xx) ⇒ rejeição definitiva, **sem retry**. |
| BR-PAY-04 | CPF do favorecido retornado pelo DICT é comparado ao informado (quando informado) — divergência ⇒ `PIX-422-040`. |
| BR-PAY-05 | Limites aplicáveis conforme **BR-LIM-01..07** (seção 3.12). |
| BR-PAY-06 | Status: `CREATED → VALIDATED → RESERVED → SENT_TO_SPI → SETTLED` ou `REJECTED`/`FAILED_TECHNICAL`/`CANCELLED`. `SETTLED` e `REJECTED` finais. `origin` ∈ {`IMMEDIATE`, `SCHEDULED`, `AUTOMATIC`}: agendamento e Pix Automático **geram uma `payment_order`** na execução (mesmo caminho, mesmas regras). |
| BR-PAY-07 | O motivo de rejeição guarda o código ISO 20022 do SPI (`AC03`, `AM04`, `AB03`…) em `reject_reason_code`. |

### 3.7 MED 2.0 (rastreamento em camadas)
| ID | Regra |
|---|---|
| BR-MED-01 | Notificação de infração exige `e2eId` existente, tipo ∈ {`FRAUD`, `OPERATIONAL_FAILURE`}, descrição e **`layer`** (camada do rastreio; a 1ª é a transação original; o BCB passou a exigir a identificação da camada no encaminhamento da notificação — etapa operacional em 2026-10-26). |
| BR-MED-02 | Abertura em até **80 dias corridos** da transação original; análise pelo PSP do recebedor em até **7 dias**; prazos parametrizados. |
| BR-MED-03 | Aceite ⇒ **bloqueio cautelar** no ledger (`reservar`, idempotente por `med-<caseId>-<n>`) até o saldo disponível; recusa/cancelamento ⇒ `cancelar` a reserva. |
| BR-MED-04 | **Rastreamento em múltiplas camadas:** se os recursos já saíram da conta, o ms abre/encaminha o caso para a **camada seguinte** (`med_case.parent_case_id`, `layer+1`), registrando o salto em `med_hop` (Pix que levou os recursos adiante). |
| BR-MED-05 | **Bloqueios e devoluções parciais conforme entra saldo:** enquanto `window_ends_at` (até **90 dias** da transação original) não vencer e `blocked_amount + refunded_amount < tracked_amount`, cada crédito novo na conta rastreada dispara bloqueio (e, após decisão, devolução) do **menor entre o saldo disponível e o valor restante**. Consumo atômico (`UPDATE … WHERE blocked+refunded+:v <= tracked`). |
| BR-MED-06 | Devolução MED usa natureza `MED_FRAUDE`/`MED_OPERACIONAL`; **não depende de saldo do usuário além do bloqueado**; é idempotente por `(e2eId, refundId)`. |
| BR-MED-07 | **Contestação pelo recebedor** de devolução feita via MED: até **80 dias a contar da devolução** (regra vigente desde 2026-09-01; antes 30). Cria `med_contestation` (`OPEN → ACCEPTED \| REJECTED \| CANCELLED`); uma contestação aberta por devolução; expira em `deadline_at`. Contestação aceita reabre o caso (`CONTESTED`). |
| BR-MED-08 | Toda transição de `med_case` MUST gerar linha em `med_case_history` (append-only, trigger impede UPDATE/DELETE) e evento na outbox. |
| BR-MED-09 | Janela de 90 dias e demais prazos MED são **[R]** em `PixPolicyProperties` (datas legais ⚠️ em `10` §2). |

### 3.8 Webhook
| ID | Regra |
|---|---|
| BR-WHK-01 | Uma URL por chave; URL MUST ser `https`, porta 443 ou configurada; mTLS opcional. |
| BR-WHK-02 | Registro valida a URL com request de verificação (sem payload sensível). |
| BR-WHK-03 | Entrega **pelo menos uma vez**, idempotente por `e2eId` (receptor deduplica); **8 tentativas** com backoff exponencial + jitter entre **5 s e 6 h**; depois `DEAD` + alerta (decisão interna, sem regra regulatória). |
| BR-WHK-05 | Entrega `DEAD` pode ser **reenviada manualmente** (`POST /v1/webhook-deliveries/{id}/resend`, escopo `webhook.write`, auditado); o reenvio mantém o `source_event_id` (idempotência no receptor). |
| BR-WHK-04 | Entrega é **assíncrona** (consumer da outbox) — nunca na thread do recebimento. |

### 3.10 Pix Agendado (pagador) — v1
| ID | Regra |
|---|---|
| BR-SCH-01 | Agendamento **único** ou **recorrente** (`ONCE`, `WEEKLY`, `BIWEEKLY`, `MONTHLY`, `BIMONTHLY`, `QUARTERLY`, `SEMIANNUAL`, `ANNUAL`) com `first_date`, `end_date` ou `occurrences_total`; a data é o **dia em `America/Sao_Paulo`**. Gera `scheduled_payment_occurrence` por data. |
| BR-SCH-02 | Na **data**, o worker cria uma `payment_order` (`origin=SCHEDULED`) pelo mesmo caminho do pagamento imediato (validação, DICT, limites **da execução**, reserva no core). Execução idempotente por `occurrence.id` ⇒ no máximo **uma** ordem por ocorrência. |
| BR-SCH-03 | **Saldo insuficiente:** nova tentativa **no mesmo dia, entre 18h e 21h** (`next_attempt_at` dentro da janela, com jitter) e **aviso ao cliente** (evento `ScheduledPaymentRetryNotified`); persistindo a falha ⇒ `FAILED_INSUFFICIENT_FUNDS`. Falha **técnica** é retentada sem aviso; falha de **negócio** (chave inexistente, CPF divergente) ⇒ `FAILED` sem retry. |
| BR-SCH-04 | Cancelamento de agendamento/ocorrência permitido enquanto `SCHEDULED` (prazo limite **[R]** — `10` E4); ocorrência em `PROCESSING` não cancela. |
| BR-SCH-05 | Recorrente: falha de uma ocorrência **não** cancela as seguintes (política configurável). |
| BR-SCH-06 | Escopo de segurança/posse: o agendamento pertence à conta do cliente autenticado; fora disso ⇒ **404**. |

### 3.11 Pix Automático (lado pagador) — v1
Modelo conforme API Pix 2.10.0 (`Rec*`, `SolicRec*`, `CobR*`); o ms é o **PSP do pagador**.
| ID | Regra |
|---|---|
| BR-AUT-01 | A **solicitação** de autorização enviada pelo recebedor (PJ) chega por evento do `spi-gateway`; estados de solicitação `CRIADA → ENVIADA → RECEBIDA → ACEITA \| REJEITADA \| EXPIRADA \| CANCELADA`; da recorrência `CRIADA → APROVADA \| REJEITADA \| EXPIRADA \| CANCELADA`. |
| BR-AUT-02 | O **pagador** (CPF/CNPJ = titular da conta, validado) **aceita ou rejeita**; rejeição usa os códigos `AP13`, `AP14`, `AP15`; aceite pode **limitar** com `payer_max_amount` (≥ `receiver_min_amount` do recebedor). |
| BR-AUT-03 | `idRec` com 29 caracteres `R` + (`R` \| `N`) + agente(8) + `yyyyMMdd` + 11; o 2º caractere define `allow_retry`. O ms **valida o formato** e deriva `allow_retry`. |
| BR-AUT-04 | O pagador **cancela** a autorização a qualquer tempo e **ajusta limites** (valor máximo); cancelamentos geram evento para o recebedor via `spi-gateway`. |
| BR-AUT-05 | **Cobrança recorrente** (`CobR`: `CRIADA → ATIVA → CONCLUIDA \| EXPIRADA \| REJEITADA \| CANCELADA`) só é executada se a autorização está `APROVADA`, o valor respeita `fixed_amount`/`payer_max_amount` e a data está na vigência (`start_date..end_date`). |
| BR-AUT-06 | **Tentativas** (`pix_automatic_attempt`): tipos `AGND` (agendada), `NTAG` (nova tentativa), `RIFL` (conforme OpenAPI), status `SOLICITADA → AGENDADA → PAGA \| CANCELADA \| REJEITADA \| EXPIRADA`; novas tentativas pós-vencimento **só se `allow_retry`**; calendário/nº de tentativas **[R]** em `PixPolicyProperties` (`10` E3). |
| BR-AUT-07 | Cada tentativa gera `payment_order` (`origin=AUTOMATIC`) com **`endToEndId` próprio** (único) e passa pelos limites (BR-LIM) — o limite noturno **não** se aplica a PJ; para PF aplica-se conforme regulamento **[R]** (`10` E3). |
| BR-AUT-08 | Débito sem autorização `APROVADA` **MUST** ser rejeitado e alertado (`PIX-422-060`). |

### 3.12 Limites (BR-LIM)
| ID | Regra |
|---|---|
| BR-LIM-01 | **Noturno (20h–6h, `America/Sao_Paulo`)**: **PF** limitada a **R$ 1.000,00** (regra do BCB); **PJ não é afetada**. Janela/valor em `PixPolicyProperties` **[R]**. |
| BR-LIM-02 | **Dispositivo não cadastrado** (`payment_order.device_registered=false`): **R$ 200,00 por transação e R$ 1.000,00 por dia**; prevalece o **menor** limite aplicável. |
| BR-LIM-03 | **Aumento** de limite solicitado pelo cliente só vale após **mínimo 24 h e máximo 48 h** (`pending_effective_at` ∈ [+24h, +48h], fixado pela política); **redução é imediata**. |
| BR-LIM-04 | Limites **diurnos** (`PER_TRANSACTION`, `DAILY`): padrão **R$ 5.000 (PF)** e **R$ 50.000 (PJ)** — política **interna**; o cliente altera **até o teto do banco** (`ceiling_amount`). |
| BR-LIM-05 | Consumo **atômico** (`UPDATE limit_usage … WHERE used+:v <= :limit`); dia = `America/Sao_Paulo`; execução de agendado/automático consome o limite **do dia da execução**. |
| BR-LIM-06 | Estouro ⇒ `REJECTED` com `PIX-422-050` e escopo violado na resposta (`errors[].code`). |
| BR-LIM-07 | Alteração de limite grava `audit_log` e publica evento. *Dúvida [R]: se o noturno é por transação ou cumulativo na janela — modelado como `NIGHTLY` por transação **e** consumo acumulado configurável.* |

### 3.9 Transversais
| ID | Regra |
|---|---|
| BR-GEN-01 | Toda mudança de estado de agregado grava **evento na outbox na mesma transação**. |
| BR-GEN-02 | Consumers são idempotentes via **inbox** `(consumer, message_id)`. |
| BR-GEN-03 | Todo agregado mutável tem `version` e `updated_at`; atualização com `If-Match` ausente ⇒ 428. |
| BR-GEN-04 | Auditoria: quem/quando/o quê em `audit_log` para operações de back-office e MED. |
| BR-GEN-05 | Nenhuma regra regulatória com número hard-coded: tudo em `PixPolicyProperties`. |

## 4. Requisitos não funcionais (resumo — detalhes em `07`)
| ID | Meta |
|---|---|
| NFR-01 | Disponibilidade 99,95% (Pix 24x7). |
| NFR-02 | p95 criar cobrança ≤ 150 ms; p95 iniciar pagamento até enviar ao SPI ≤ 3 s. |
| NFR-03 | Processar 300 rps sustentado, pico 1.000 rps (cobranças/consultas). |
| NFR-04 | RPO ≤ 1 min, RTO ≤ 15 min; PITR no PostgreSQL. |
| NFR-05 | Lag de consumer < 1.000 mensagens / < 30 s por 5 min (alerta). |
| NFR-06 | LGPD: PII criptografada, mascarada, com retenção definida. |

## 5. Cenários de aceitação (Gherkin, em inglês) — amostra normativa
```gherkin
Feature: CPF validation (FR-DOC-01..04)
  Scenario Outline: CPF input
    When I validate the document "<cpf>"
    Then the result is "<result>"
    Examples:
      | cpf             | result  |
      | 529.982.247-25  | valid   |
      | 52998224725     | valid   |
      | 111.111.111-11  | invalid |
      | 529.982.247-24  | invalid |
      | 1234567890      | invalid |
      |                 | invalid |

Feature: Alphanumeric CNPJ (FR-DOC-03, BR-DOC-05)
  Scenario Outline: CNPJ input
    When I validate the document "<cnpj>"
    Then the result is "<result>"
    Examples:
      | cnpj                | result  |
      | 12.ABC.345/01DE-35  | valid   |
      | 12abc34501de35      | valid   |
      | 11.222.333/0001-81  | valid   |
      | 12ABC34501DE36      | invalid |
      | 00000000000000      | invalid |
      | 12ABC34501DE3A      | invalid |

Feature: Immediate charge (FR-COB-01, BR-COB-01..07)
  Scenario: Create a charge with a client-provided txid
    Given an authenticated receiver with the active key "529.982.247-25"
    When I send PUT /api/pix/v2/cob/"7978c0c97ea847e78e8849634473c1f1" with valor.original "123.45" and expiracao 3600
    Then I get 201 with status "ATIVA" and the location filled in
    And there is 1 "ChargeCreated" event in the outbox
  Scenario: Repeated txid for the same receiver
    Given the charge "7978c0c97ea847e78e8849634473c1f1" already exists with a different body
    When I send PUT with the same txid
    Then I get 409 with code "PIX-409-020"
  Scenario: Payment after expiration
    Given a charge created 3601 s ago with expiracao 3600
    When the SPI reports a Pix with that txid
    Then the Pix is stored with reconciliation_status "MATCHED_AFTER_EXPIRATION" and the charge stays "ATIVA" until the reconciliation rule applies

Feature: Idempotent receipt (BR-PIX-01, BR-GEN-02)
  Scenario: Duplicate SPI message
    Given the event "pix.spi.payment-settled.v1" with e2eId "E1234…" was already processed
    When the same event arrives again
    Then no new Pix is stored and the offset is committed

Feature: Refund (BR-REF-01..05)
  Scenario: Refund above the refundable amount
    Given a Pix of "100.00" with settled refunds of "70.00"
    When I request a refund of "40.00"
    Then I get 422 with code "PIX-422-030"
  Scenario: Idempotent repetition
    Given refund "D1" of "30.00" was already created
    When I repeat the PUT with the same body
    Then I get 200 with the same rtrId/status and no new message to the SPI

Feature: Payment (BR-PAY-01..07)
  Scenario: Core outage does not burn the Idempotency-Key
    Given the core ledger is unavailable
    When I POST /v1/payment-orders with Idempotency-Key "K1"
    Then I get 503 and key "K1" stays free
    When the core is back and I repeat with "K1" and the same body
    Then the order is created exactly once, with a single reservation keyed by its endToEndId

Feature: Limits (BR-LIM-01..05)
  Scenario: Individual (PF) above the night limit
    Given a PF account and it is 21:00 in America/Sao_Paulo
    When I request a payment of "1000.01"
    Then the order is REJECTED with code "PIX-422-050"
  Scenario: Business (PJ) is not subject to the night limit
    Given a PJ account with a daytime limit of "50000.00" and it is 21:00
    When I request a payment of "20000.00"
    Then the order moves to RESERVED
  Scenario: Unregistered device
    Given an unregistered device and "900.00" already used today
    When I request a payment of "150.00"
    Then it is REJECTED by the unregistered-device daily limit
  Scenario: Increase only after the waiting period
    When I request raising the daily limit to "8000.00" at 10:00
    Then the effective limit stays "5000.00" and pending_effective_at is between +24h and +48h
    And lowering it to "3000.00" takes effect immediately

Feature: Scheduled Pix (BR-SCH-01..04)
  Scenario: Insufficient funds on the due date
    Given a schedule of "300.00" due today and the core answers insufficient funds at 06:00
    Then the occurrence is RETRY_PENDING with next_attempt_at between 18:00 and 21:00 and the customer is notified
    When funds exist at 19:10 and the retry runs
    Then exactly 1 payment_order with origin SCHEDULED is created and the occurrence becomes EXECUTED

Feature: Automatic Pix, payer side (BR-AUT-01..08)
  Scenario: Recurring charge without an approved authorization
    Given an authorization in CRIADA
    When a recurring charge arrives for it
    Then the charge is REJEITADA with code "PIX-422-060" and nothing is debited

Feature: MED 2.0 (BR-MED-04..07)
  Scenario: Funds already left the account - next layer
    Given an accepted case of "500.00" whose account balance is "120.00" and the rest left through Pix E_X
    Then "120.00" is blocked at layer 1 and a layer 2 case (parent = case 1) is opened with tracked "380.00"
    And when a new credit of "200.00" reaches the layer 2 account, "200.00" is blocked and "180.00" remains tracked
  Scenario: Contestation within 80 days of the refund
    Given a MED refund settled 79 days ago
    When the receiver contests it
    Then the contestation is OPEN; at 81 days it is rejected with "PIX-422-070"

Feature: Key claim (BR-KEY-07)
  Scenario: Donor does not answer in time
    Given a WAITING_RESOLUTION portability claim created 7 days and 1 minute ago
    When the expiration job runs
    Then the claim becomes CONFIRMED automatically and "KeyClaimStatusChanged" is published
```

## 6. Regras como tipos (aplicação de `code-design`)
- `Cpf`, `Cnpj`, `Documento` (sealed: `Cpf | Cnpj`), `TxId`, `EndToEndId`, `Money`, `PixKeyValue(type,value)`, `Ispb`, `ExpirationSeconds`.
- `Charge.markPaid(ReceivedPix)`, `Charge.remove(Actor)`, `Refund.settle(rtrId, at)` — sem setters; transições passam por **um** método que valida `canTransitionTo`.
- Falhas esperadas: `sealed interface CreateChargeResult permits Created, TxIdConflict, KeyNotActive, InvalidDebtor`.

## 7. Máquinas de estado
```
Charge:        ATIVA ──pago──► CONCLUIDA
                 ├──remove usuário──► REMOVIDA_PELO_USUARIO_RECEBEDOR
                 └──remove PSP──────► REMOVIDA_PELO_PSP
Refund:        EM_PROCESSAMENTO ──► DEVOLVIDO | NAO_REALIZADO
PaymentOrder:  CREATED → VALIDATED → RESERVED → SENT_TO_SPI → SETTLED (core: confirmar)
                  └────────────→ REJECTED | FAILED_TECHNICAL | CANCELLED (core: cancelar)
PixKey:        PENDING_OWNERSHIP_PROOF → PENDING_DICT → ACTIVE → (PORTABILITY_REQUESTED | OWNERSHIP_CLAIMED) → DELETED
KeyClaim:      OPEN → WAITING_RESOLUTION → CONFIRMED → COMPLETED | CANCELLED
MedCase:       OPEN → ACCEPTED → REFUNDING → CLOSED | REFUSED | CANCELLED | CONTESTED   (layer N → layer N+1 via med_hop)
ScheduledOccurrence: SCHEDULED → PROCESSING → EXECUTED | RETRY_PENDING → EXECUTED | FAILED_INSUFFICIENT_FUNDS | FAILED | CANCELLED
AutomaticAuth: CRIADA → APROVADA → (CANCELADA) | REJEITADA | EXPIRADA ; AutomaticCharge: CRIADA → ATIVA → CONCLUIDA | EXPIRADA | REJEITADA | CANCELADA
WebhookDelivery: PENDING → DELIVERED | RETRYING → DEAD
```

## 8. Matriz de rastreabilidade (FR → BR → tabela → endpoint/evento → teste)
| RF | RN | Tabelas | API / Evento | Teste (`08`) |
|---|---|---|---|---|
| FR-DOC-01..04 | BR-DOC-01..04 | `account_holder`, todas com `*_document_hash` | todas | `CpfTest`, `CnpjTest`, `CpfValidatorWebTest` |
| FR-KEY-01..06 | BR-KEY-01..08 | `pix_key`, `pix_key_claim`, `dict_sync_state` | `/v1/pix-keys`, `/v1/key-claims`; `pix.key.*` | `PixKeyServiceTest`, `DictClientContractTest`, `KeyClaimIT` |
| FR-COB-01..04 | BR-COB-01..10 | `pix_charge`, `pix_charge_due_terms`, `pix_charge_revision`, `pix_location`, `charge_additional_info` | `/api/pix/v2/cob*`, `loc*`; `pix.charge.*` | `ChargeServiceTest`, `ChargeControllerWebTest`, `ChargeRepositoryIT` |
| FR-PIX-01 | BR-PIX-01..04 | `pix_received`, `pix_received_component` | `/api/pix/v2/pix`; consumer `pix.spi.payment-settled.v1` | `ReceivePixConsumerIT`, `ReceivePixIdempotencyIT` |
| FR-REF-01 | BR-REF-01..07 | `pix_refund`, `ledger_reservation` | `/api/pix/v2/pix/{e2eid}/devolucao/{id}`; `pix.refund.*` | `RefundServiceTest`, `RefundConcurrencyTest` |
| FR-WHK-01 | BR-WHK-01..04 | `webhook_subscription`, `webhook_delivery` | `/api/pix/v2/webhook*` | `WebhookDispatcherIT` |
| FR-PAY-01..03, FR-LIM-01..02 | BR-PAY-01..07, BR-LIM-01..07 | `payment_order`, `pix_limit`, `limit_usage`, `trusted_device`, `ledger_reservation`, `spi_message` | `/v1/payment-orders`, `/v1/limits`; `pix.payment.*` | `PaymentOrderServiceTest`, `PaymentIdempotencyIT` |
| FR-MED-01..05 | BR-MED-01..09 | `med_case`, `med_case_history`, `med_block`, `med_hop`, `med_contestation` | `/v1/med-cases`; `pix.med.*` | `MedCaseServiceTest`, `MedLayerTrackingIT`, `MedContestationServiceTest`, `MedHistoryAppendOnlyIT` |
| FR-SCH-01 | BR-SCH-01..06 | `scheduled_payment`, `scheduled_payment_occurrence` | `/v1/scheduled-payments`; `pix.schedule.*` | `ScheduledPaymentServiceTest`, `ScheduleRetryWindowIT` |
| FR-AUT-01 | BR-AUT-01..08 | `pix_automatic_authorization`, `_charge`, `_attempt` | `/v1/automatic-authorizations`; `pix.automatic.*`, `pix.spi.automatic-*` | `AutomaticAuthorizationServiceTest`, `AutomaticChargeConsumerIT` |
| FR-WHK-02 | BR-WHK-05 | `webhook_delivery` | `POST /v1/webhook-deliveries/{id}/resend` | `WebhookResendIT` |
