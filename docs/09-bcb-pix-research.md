# 09 — Pesquisa da documentação oficial do Pix (BCB)

> Levantamento feito em **2026-10-15**. Todo campo/regra marcado **[V]** foi lido diretamente da fonte oficial nesta data; **[R]** vem do Regulamento/Manuais e **precisa ser reconferido** antes de implementar (não foi aberto no PDF oficial nesta sessão).

## 1. Fontes e versões vigentes

| Documento | Versão vista | Fonte | Verificação |
|---|---|---|---|
| API Pix (recebimento: cob, cobv, loc, pix, devolução, webhook) | **2.10.0** (`info.version`) | `https://github.com/bacen/pix-api` (`openapi.yaml`, 9.124 linhas, baixado em 2026-10-15) | **[V]** |
| API DICT (chaves, reivindicação, notificação de infração, solicitação de devolução) | **2.12.1** | `https://www.bcb.gov.br/content/estabilidadefinanceira/pix/API-DICT.html` | **[V]** (trecho 0–100k de 161k caracteres) |
| Manual de Padrões para Iniciação do Pix | **2.10.0** (IN BCB nº 769, 19/08/2026, revoga IN 658/2025) segundo fonte secundária; PDF indexado no bcb.gov.br ainda mostrava 2.9.0 | Atlas Público / bcb.gov.br | **[R]** — confirmar no DOU/BCB |
| Manual das Interfaces de Comunicação (SPI/DICT, mensagens ISO 20022) | 1.11 visto em resultado de busca; última não confirmada | bcb.gov.br | **[R]** |
| Regulamento Pix, Manual de Segurança, Manual Operacional do DICT, MED | não abertos | bcb.gov.br/estabilidadefinanceira/pix | **[R]** |

**Ação de governança:** `docs/09` é re-executada a cada release do BCB. O repositório guarda o `openapi.yaml` do BCB usado como baseline em `docs/api/bcb/` (ver backlog) e um teste de contrato compara o `info.version`.

## 2. Regras de dados extraídas do OpenAPI 2.10.0 **[V]**

| Conceito | Regra oficial | Impacto no modelo |
|---|---|---|
| `txid` (cobrança) | `[a-zA-Z0-9]{26,35}`; criado pelo recebedor; **único por CPF/CNPJ do recebedor** (PSP valida) | `uk_pix_charge_receiver_txid (receiver_document_hash, txid)` |
| `txid` em Pix recebido | `[a-zA-Z0-9]{1,35}` (pode ser Pix sem cobrança) | coluna `txid` `varchar(35)` nullable |
| `endToEndId` | 32 caracteres `[a-zA-Z0-9]{32}` | `varchar(32)`, único |
| `rtrId` (devolução) | 32 caracteres (`ReturnIdentification`, pacs.004) | `varchar(32)` |
| `DevolucaoId` | `[a-zA-Z0-9]{1,35}`, gerado pelo cliente | idempotência por `(end_to_end_id, refund_id)` |
| `valor` | string `\d{1,10}\.\d{2}` | `numeric(12,2)`; API usa string decimal |
| `chave` (DICT na cobrança) | `maxLength 77`; tipos: telefone, e-mail, CPF/CNPJ, EVP | `varchar(77)` |
| `solicitacaoPagador` | máx. 140 | `varchar(140)` |
| `infoAdicionais` | itens `{nome ≤50, valor ≤200}`; até 50 itens | tabela filha ou `jsonb` validado |
| Devolução `descricao`/`motivo` | máx. 140 | `varchar(140)` |
| `expiracao` (cob imediata) | inteiro em segundos a partir de `calendario.criacao`; default **86400** | `expiration_seconds int default 86400` |
| `dataDeVencimento` + `validadeAposVencimento` (cobv) | data ISO e dias corridos após o vencimento | `due_date date`, `validity_after_due_days int` |
| Status da cobrança | `ATIVA`, `CONCLUIDA`, `REMOVIDA_PELO_USUARIO_RECEBEDOR`, `REMOVIDA_PELO_PSP` (o texto descritivo do BCB escreve `REMOVIDO_...` no masculino, mas o **enum** é `REMOVIDA_...`) | CHECK constraint com o enum |
| Status da devolução | `EM_PROCESSAMENTO`, `DEVOLVIDO`, `NAO_REALIZADO` | idem |
| Natureza da devolução | `ORIGINAL`, `RETIRADA`, `MED_OPERACIONAL`, `MED_FRAUDE`, `MED_PIX_AUTOMATICO` (códigos pacs.004: MD06, SL02, BE08, FR01) | enum `refund_nature` |
| Limite da devolução | ≤ valor do Pix; Saque → valor da retirada; Troco → compra (ORIGINAL) ou troco (RETIRADA) | regra BR-REF-03/04 |
| Composição do valor | `valor = original + saque + troco + multa + juros − abatimento − desconto` | tabela `pix_received_component` |
| Lote de cobv | status `EM_PROCESSAMENTO`, `CRIADA`, `NEGADA` | backlog (lote) |
| Scopes OAuth2 (client credentials) | `cob.read/write`, `cobv.read/write`, `lotecobv.*`, `pix.read/write`, `payloadlocation.read/write`, `webhook.read/write`, `rec.*`, `solicrec.*`, `cobr.*` | mapeamento para `@PreAuthorize` |
| Paths | `/cob`, `/cob/{txid}`, `/cobv`, `/cobv/{txid}`, `/lotecobv`, `/loc`, `/loc/{id}`, `/loc/{id}/txid`, `/locrec`, `/pix`, `/pix/{e2eid}`, `/pix/{e2eid}/devolucao/{id}`, `/webhook/{chave}`, `/webhook`, `/webhookrec`, `/webhookcobr`, payloads JWS públicos | espelhados em `api/pix/v2` |
| CPF (query/schema) | `/^\d{11}$/` — **atenção**: o pattern publicado contém as barras `/` literais, o que o torna inválido como regex; o tratamento correto é `^\d{11}$` | usar `^\d{11}$` + dígito verificador |
| CNPJ | `^[0-9A-Z]{14}$` (alfanumérico — formato da **Receita Federal**) | VO `Cnpj` aceita letras; **DV sempre validado** (BR-DOC-05) |

## 3. Regras do DICT 2.12.1 **[V]**

| Item | Regra |
|---|---|
| Tipos de chave | `CPF` (11 dígitos), `CNPJ` (14 alfanuméricos, case-insensitive), `PHONE` (`+` + dígito ≠0, até 15 dígitos, E.164), `EMAIL` (minúsculo, ≤77, padrão de e-mail), `EVP` (UUID minúsculo 8-4-4-4-12 gerado pelo DICT) |
| Tamanho | todas as chaves ≤ 77 caracteres |
| Recursos | `entries` (criar, consultar com estatísticas, atualizar, excluir), `keys/check`, `claims` (tipos posse e portabilidade: create, list, get, acknowledge, confirm, cancel, complete), `infraction reports` e `refunds` sob `funds-recoveries/{id}/…` (create, list, get, update, acknowledge, cancel, close) |
| Segurança | mTLS; requisições de escrita carregam **assinatura XML digital**; respostas são assinadas e o cliente deve validar (detalhes no Manual de Segurança) |
| Rate limit | token bucket por operação; HTTP 429 ao exceder. Ex.: criar/excluir entry 1.200/min (balde 36.000); atualizar entry 600/min; `keys/check` 70/min; criar/agir em claim 1.200/min; ler claim 600/min; listar claims 40/min com filtro de papel, 10/min sem; escrita de refund 2.400/min; consulta de entry com limite variável por categoria do PSP e tipo do pagador (PF/PJ) |
| Ambientes | homologação e produção em hosts/portas distintos |

> Os limites acima **mudam**; devem ser parametrizados (`pix.dict.rate-limit.*`) e o cliente DICT deve implementar *token bucket* local abaixo do limite oficial (ver `07-non-functional.md`).

## 4. Regras do Regulamento/Manuais **[R]** — validar antes de codar

| Regra | Valor assumido nesta spec | Onde |
|---|---|---|
| Prazo máximo para devolução comum | 90 dias após o Pix | BR-REF-05 (parametrizável) |
| Janela para registrar notificação de infração (MED) | até 80 dias | BR-MED-02 (parametrizável) |
| Prazo de reivindicação | Reivindicador: cancela se o usuário não validar a chave até o **30º dia** (confirmado pelo solicitante); doador: 7 dias (⚠️ Manual de Tempos) | BR-KEY-07 |
| Limite noturno (20h–6h) | R$ 1.000 **PF**; PJ isenta; aumento 24–48 h | BR-LIM-01..03 |
| Quantidade máxima de chaves por conta | PF 5 / PJ 20 **por conta** (Manual Operacional do DICT) | BR-KEY-03 |
| Chave CPF/CNPJ deve pertencer ao titular da conta | sim | BR-KEY-02 |
| Pix é 24x7x365; liquidação em segundos | SLO interno p95 ≤ 3 s para iniciar → enviar ao SPI | `07-non-functional.md` |
| ISO 20022: pacs.008 (pagamento), pacs.002 (status), pacs.004 (devolução), camt.* | adapter SPI | `03-architecture.md` |

Todos esses valores ficam em `PixPolicyProperties` (config externa), **nunca** hard-coded (lição de `resilience-patterns`: propriedade declarada e não lida é defeito).

## 4b. Atualização 2026-10-15 (informado pelo solicitante, com checagem parcial)

| Tema | Informação | Verificação |
|---|---|---|
| Manual de Padrões para Iniciação do Pix | **2.10.0** (IN BCB 769, 19/08/2026; revoga IN 658/2.9.0): forma de iniciação `AUTO`, campos de recorrência, QR Code Composto | ✅ coincide com a busca (fonte secundária) |
| Manual do DICT | **8.5** (jul/2026) | ⚠️ não verificado |
| **CNPJ alfanumérico** | Formato **da Receita Federal** (não do BCB), 12 posições `[0-9A-Z]` + 2 DV numéricos; módulo 11 com valor = ASCII − 48; coexiste com o numérico; 1ª emissão em jul/2026 | ✅ algoritmo/coexistência (várias fontes + exemplo `12.ABC.345/01DE-35` validado por script); ⚠️ data de 1ª emissão (uma fonte) |
| **MED 2.0** | BCB: em produção desde **11/05/2026** (rastreamento em camadas); obrigatoriedade desde fev/2026 foi o que o solicitante informou, **sem confirmação** nas fontes que achei; ajuste "camada" no encaminhamento da notificação com etapa operacional em **26/10/2026** | ✅ produção/camadas e ajuste de 26/10; ⚠️ data de obrigatoriedade |
| **Contestação da devolução MED** | Recebedor tem **80 dias a contar da devolução** (antes 30), desde **01/09/2026** (IN BCB 766 atualizou o Manual Operacional do DICT); vítima tem 80 dias da transação | ✅ |
| Devolução (janela) | 90 dias da transação original; no MED, bloqueios/devoluções parciais conforme entra saldo, dentro desse prazo | ⚠️ informado |
| Pix Agendado | recorrente obrigatório desde out/2024; retry no mesmo dia **18h–21h** com aviso | ⚠️ informado |
| Pix Automático | oferta obrigatória ao pagador; **modelo de dados** (`idRec` 29 chars, estados, tentativas `AGND/NTAG/RIFL`, rejeição `AP13/14/15`) **[V]** no OpenAPI 2.10.0 | ✅ modelo; ⚠️ calendário de tentativas |
| Limites | noturno PF R$1.000 (20h–6h); aumento 24–48 h; redução imediata; dispositivo não cadastrado R$200/tx e R$1.000/dia | ⚠️ informado |
| Chaves por conta | PF 5 / PJ 20 (por **conta**) | ⚠️ informado |
| Prazo do doador (reivindicação) | 7 dias — **Manual de Tempos do Pix** | ⚠️ **não verificado** |

### Modelo de recorrência extraído do OpenAPI 2.10.0 **[V]**
`RecStatus`: `CRIADA, APROVADA, REJEITADA, EXPIRADA, CANCELADA` · `SolicRecStatus`: `CRIADA, ENVIADA, RECEBIDA, REJEITADA, ACEITA, EXPIRADA, CANCELADA` · `CobRStatus`: `CRIADA, ATIVA, CONCLUIDA, EXPIRADA, REJEITADA, CANCELADA` · `periodicidade`: `SEMANAL, MENSAL, TRIMESTRAL, SEMESTRAL, ANUAL` · `idRec` 29 chars `R[R|N]` + agente(8) + `yyyyMMdd` + 11 · `vinculo.contrato`/`objeto` ≤ 35 · rejeição `AP13/AP14/AP15` · `tentativas[].tipo ∈ {AGND, NTAG, RIFL}`, `status ∈ {SOLICITADA, AGENDADA, PAGA, CANCELADA, REJEITADA, EXPIRADA}` · `valorRec`, `valorMinimoRecebedor`.

## 5. Divergências/armadilhas já encontradas no próprio BCB

1. Pattern de CPF com barras literais (`"/^\\d{11}$/"`) — inválido como regex.
2. `infoAdicionais` usa `maximum: 50` (palavra-chave numérica) onde o correto seria `maxItems: 50`.
3. Descrição de status usa `REMOVIDO_*` e enum usa `REMOVIDA_*`.
4. Pattern de `TxId` difere entre contextos (`{26,35}` na cobrança, `{1,35}` no Pix recebido e no filtro `/pix?txid=`).

→ A spec do ms adota o **enum/pattern normativo** e documenta cada divergência em teste de contrato.
