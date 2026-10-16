# 05 — Contratos de API (REST)

> Skill: `api-contract-review` (checklist §9). Baseline BCB copiado em `docs/api/bcb/api-pix-openapi-2.10.0.yaml` (Apache-2.0, sha256 `5c39d711…4310`, baixado 2026-10-15). Contrato de gestão: `docs/api/ms-pix-manager.openapi.yaml`.

## 1. Duas superfícies, uma regra por superfície (ADR-003)

| Superfície | Base path | Consumidor | Regra de naming |
|---|---|---|---|
| **Compat BCB** | `/api/pix/v2` | Clientes que falam a API Pix padrão | **Literal do OpenAPI 2.10.0** (`/cob`, `/cobv`, `/loc`, `/pix`, `/webhook`, campos em pt: `txid`, `chave`, `valor.original`, `devedor`, `calendario`) — não renomear (wire contract) |
| **Gestão** | `/v1` | Back-office, apps, parceiros | Recursos **plurais**, `kebab-case`, JSON `camelCase` em **inglês**; termos regulatórios literais |
Versão no path em ambas; `/v2` de gestão entra como novo controller sem mexer no `/v1`. Mudança na superfície BCB só quando o BCB publicar nova versão (teste de contrato falha se `info.version` do baseline mudar).

## 2. Endpoints — Compat BCB (escopos OAuth2 do OpenAPI)

| Método | Rota | Escopo | Status de sucesso | Observações |
|---|---|---|---|---|
| PUT | `/cob/{txid}` | `cob.write` | 201 | `txid` do cliente `[a-zA-Z0-9]{26,35}`; idempotente por `txid` (corpo igual ⇒ 201/200 com o mesmo recurso; diferente ⇒ 409 `PIX-409-020`) |
| POST | `/cob` | `cob.write` | 201 | `txid` gerado pelo PSP (32 chars) |
| PATCH | `/cob/{txid}` | `cob.write` | 200 | revisão; só `ATIVA`; `If-Match` exigido (428) |
| GET | `/cob/{txid}` | `cob.read` | 200 | `?revisao=n`; ETag |
| GET | `/cob` | `cob.read` | 200 | `inicio`, `fim` (RFC 3339, `inicio ≤ fim`), `cpf`/`cnpj` (exclusivos), `status`, `locationPresente`, `paginaAtual`, `itensPorPagina` (≤100) — **paginação do BCB** (`parametros.paginacao`) |
| PUT/POST/PATCH/GET | `/cobv/{txid}`, `/cobv` | `cobv.write/read` | idem | vencimento, multa, juros, abatimento, desconto |
| POST/GET | `/loc`, `/loc/{id}` | `payloadlocation.write/read` | 201/200 | |
| DELETE | `/loc/{id}/txid` | `payloadlocation.write` | 200 | desvincula `txid` (**sem body**) |
| GET | `/pix`, `/pix/{e2eid}` | `pix.read` | 200 | `inicio`,`fim`,`txid`,`txIdPresente`,`devolucaoPresente`,`cpf|cnpj`, paginação |
| PUT | `/pix/{e2eid}/devolucao/{id}` | `pix.write` | 201 | idempotente (BR-REF-01) |
| GET | `/pix/{e2eid}/devolucao/{id}` | `pix.read` | 200 | |
| PUT/GET/DELETE | `/webhook/{chave}`, GET `/webhook` | `webhook.write/read` | PUT **201** novo / **200** atualiza · GET 200 · DELETE 204 · 404 chave de outra conta/sem webhook · **422 `PIX-422-017`** URL fora da política | **Implementado.** Só `https` (o banco também exige: `CHECK url ~ '^https://'`), sem credencial/fragmento na URL, host público (anti-SSRF: bloqueia loopback, privado, link-local/metadados 169.254.x, CGNAT, ULA, multicast), checado no cadastro **e a cada envio**. A chave precisa estar `ACTIVE` e ser do chamador. Veja “Entrega do webhook” abaixo |
| GET | `/{pixUrlAccessToken}`, `/cobv/{pixUrlAccessToken}` | público (JWS) | 200 | `application/jose`; resposta assinada |
Fora da v1 (backlog): `/lotecobv`, `/locrec`, `/rec`, `/solicrec`, `/cobr`, `/webhookrec`, `/webhookcobr`.

**Segurança**: mTLS **terminado no gateway** (o ms não faz mTLS de entrada); o ms é **OAuth2 Resource Server** (valida JWT: assinatura via JWKS, `iss`, `aud`, `exp`; **não emite token**); `client_credentials` emitido por IdP externo (Keycloak só em dev/teste); `@PreAuthorize("hasAuthority('SCOPE_cob.write')")`; escopo + **posse** (a cobrança/chave pertence à conta do `client_id`; caso contrário **404**, não 403, para não vazar existência — decisão de information hiding da skill).

## 3. Endpoints — Gestão (`/v1`)

| Recurso | Operações |
|---|---|
| `/v1/pix-keys` | `POST` (registrar) · `GET` (listar do titular, paginado) · `GET /{id}` · `POST /{id}/ownership-proof/challenges` (emite o código OTP, **202**) · `POST /{id}/ownership-proof/verifications` (confere o código, `{ "code": "123456" }`) · `POST /{id}/deletion` (motivo) · `PUT /{id}/account` (atualizar conta) · `POST /lookups` (consultar chave de terceiro no DICT; **POST** porque o valor da chave é PII e não deve ir em URL/log de acesso) · `POST /existence-checks` (`keys/check`) |
| `/v1/key-claims` | `POST` · `GET` · `GET /{id}` · `POST /{id}/acknowledgement` · `/confirmation` · `/cancellation` · `/completion` |
| `/v1/payment-orders` | `POST` (**`Idempotency-Key` obrigatório**) · `GET /{id}` · `GET` (paginado) · `POST /{id}/cancellation` |
| `/v1/payment-orders/decodings` | `POST` (decodifica BR Code/QR → dados do favorecido, sem criar ordem) |
| `/v1/limits` | `GET /accounts/{accountId}` · `PUT /accounts/{accountId}/{scope}` (**aumento só vale após 24–48 h**, resposta traz `pendingEffectiveAt`; **redução imediata**; valor ≤ teto do banco) · `GET/POST/DELETE /accounts/{accountId}/trusted-devices` |
| `/v1/med-cases` | **Implementado** (escopos `med.read`/`med.write`). `POST` (**`Idempotency-Key`**; `role` REPORTER\|REPORTED, `endToEndId`, `caseType`, `description`, `layer`; 404 Pix inexistente · 409 já há caso na camada · 422 `PIX-422-071` fora dos 80 dias) · `GET` (`status`; paginado) · `GET /{id}` · `GET /{id}/history` (trilha append-only) · REPORTED: `POST /{id}/acceptance` (bloqueia o valor no core) · `/refusal` · `/refund` (libera o bloqueio e devolve com natureza `MED_FRAUDE`/`MED_OPERACIONAL`) · REPORTER: `POST /{id}/closure` · ambos: `POST /{id}/cancellation` · `POST /{id}/contestations` (80 dias da devolução; 422 `PIX-422-070`) · `POST /contestations/{cid}/decision`. **Não implementado:** `GET /{id}/hops` e o rastreamento automático em camadas |
| `/v1/scheduled-payments` | **Implementado** (`schedule.read`/`schedule.write`). `POST` (**`Idempotency-Key` obrigatório**; `recurrence` ONCE\|WEEKLY\|BIWEEKLY\|MONTHLY\|BIMONTHLY\|QUARTERLY\|SEMIANNUAL\|ANNUAL, `method` KEY\|MANUAL, `firstDate` (posterior a hoje, dia em America/Sao_Paulo), `endDate` XOR `occurrencesTotal` (2–120); 422 `PIX-422-061`) · `GET` (paginado) · `GET /{id}` (ETag) · `GET /{id}/occurrences` · `POST /{id}/cancellation` (`If-Match`) · `POST /{id}/occurrences/{oid}/cancellation` (422 `PIX-422-062` fora do estado). Execução: worker a cada 30 s; ver "Pix Agendado — execução" abaixo |
| `/v1/automatic-authorizations` | **Implementado, lado pagador** (`automatic.read`/`automatic.write`). `POST` (entrada da solicitação do recebedor: no produto vem do spi-gateway; `idRec` `R(R\|N)`+27; documento do pagador = titular, senão 422) · `GET` · `GET /{id}` · `POST /{id}/approval` (opcional `payerMaxAmount`) · `/rejection` (`AP13`/`AP14`/`AP15`) · `/cancellation` (`cancelledBy` PAYER\|RECEIVER\|PSP) · `PUT /{id}/limit` · `POST /{id}/charges` (entrada da cobrança; 422 `PIX-422-060` sem aprovação) · `GET /{id}/charges` · `GET /{id}/charges/{cid}/attempts` · `POST /{id}/charges/{cid}/cancellation` (até 23h59 da véspera) |
| `/v1/webhook-deliveries` | `POST /{id}/resend` — **implementado** (entrega `DEAD` ⇒ `PENDING`; escopo `webhook.admin` **e** acesso a todas as contas; 403/404/422). `GET` (listar/filtrar entregas) ainda **não** implementado |
| `/v1/reports/reconciliation` | `GET` (lê a MV; `day` em `America/Sao_Paulo`) |
Cancelamentos e transições são **sub-recurso substantivo** (`/cancellation`, `/closure`), nunca `DELETE` com corpo nem `GET` com efeito (lições da skill).

## 4. Convenções transversais

### 4.1 Concorrência otimista
- Respostas de agregados trazem `ETag: "<version>"`.
- `PUT`/`PATCH`/`POST …/transição` que alteram agregado **exigem** `If-Match`; ausente ⇒ **428 Precondition Required** (`PIX-428-001`); malformado ⇒ **400**; versão desatualizada ⇒ **412** (`PIX-412-001`). `*` significa "qualquer representação existente".
- `GET` honra `If-None-Match` ⇒ **304** (`checkNotModified`).

### 4.2 Idempotência (`Idempotency-Key`; draft IETF, não RFC)
1. Obrigatório em `POST /v1/payment-orders`, `POST /v1/med-cases`, `POST /v1/key-claims`, `POST /v1/pix-keys`; opcional nos demais `POST`.
2. Armazenado em `idempotency_record (client_id, operation, key)` com `request_hash` do corpo canônico.
3. Primeira requisição: cria `IN_PROGRESS` (lease). Concluída com **commit** ⇒ `COMPLETED` + resposta guardada. **Falha técnica (5xx, timeout) libera a chave** (nunca "queima").
4. Repetição com mesmo corpo ⇒ **mesma resposta** (status, headers relevantes, corpo) + header `Idempotent-Replayed: true`.
5. Mesma chave + corpo diferente ⇒ **422** `PIX-422-900`.
6. Requisição concorrente com a mesma chave `IN_PROGRESS` ⇒ **409** `PIX-409-900` + `Retry-After`.
7. Escopo da chave: `client_id` (não global). TTL 24 h (7 d p/ MED).

### 4.3 Paginação
- Compat BCB: `paginacao {paginaAtual, itensPorPagina, quantidadeDePaginas, quantidadeTotalDeItens}` (formato oficial).
- Gestão: envelope único `{ "content": [...], "page": 0, "size": 20, "totalElements": 0, "totalPages": 0 }`; `size` ≤ 100, default **20 em todos** (sem defaults divergentes). ⚠️ **Header `Link` (RFC 8288) ainda NÃO é emitido**: a navegação é pelos campos do corpo (`page`/`totalPages`). O envelope é o `PageResult` (`totalPages` é calculado e serializado; coberto por `PageResultJsonTest`). **Toda** coleção que pode crescer é paginada (histórico, entregas de webhook, claims).
- Filtros de intervalo validam `inicio ≤ fim` ⇒ 400.

### 4.4 Erros — RFC 9457 `application/problem+json`
```json
{
  "type": "https://errors.pixmanager.example/PIX-422-001",
  "title": "Documento inválido",
  "status": 422,
  "detail": "O CPF informado é inválido.",
  "instance": "/v1/pix-keys",
  "code": "PIX-422-001",
  "timestamp": "2026-10-15T14:03:22Z",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "errors": [{ "field": "document", "code": "CPF_INVALID", "message": "CPF inválido" }]
}
```
- Propriedades de extensão em **inglês** (`code`, `errors`, `timestamp`, `traceId`) — decisão tomada **antes** de existir cliente (lição de mistura pt/en).
- `type` base configurável (`pix.errors.base-type-uri`). `detail` nunca contém PII, SQL ou stack trace; `traceId` permite achar o log.
- Handlers dedicados para 405, 406, 415, 429 (`Retry-After`), 400 de parse, 401/403 do Resource Server (corpo Problem Details via `AuthenticationEntryPoint`/`AccessDeniedHandler`). **Nenhum cai no 500 genérico.**

### 4.5 Catálogo de códigos (`ErrorCodes`) — extrato
| Código | HTTP | Significado |
|---|---|---|
| PIX-400-001 | 400 | Corpo/parâmetro inválido (Bean Validation) |
| PIX-400-002 | 400 | `If-Match` malformado / intervalo `inicio>fim` |
| PIX-401-001 / 403-001 | 401/403 | Não autenticado / sem escopo |
| PIX-404-001 | 404 | Recurso inexistente **ou** fora do escopo do cliente |
| PIX-409-020 | 409 | `txid` já existe para o recebedor (corpo diferente) |
| PIX-409-900 | 409 | Requisição idêntica em andamento |
| PIX-412-001 | 412 | Versão desatualizada (If-Match) |
| PIX-422-001 | 422 | CPF inválido |
| PIX-422-003 | 422 | Valor monetário ou `endToEndId` fora do formato (ex.: `ddd.dd`, valor acima de 9999999999.99) |
| PIX-422-002 | 422 | CNPJ inválido (numérico ou alfanumérico) |
| PIX-422-010 | 422 | Chave não pertence ao titular da conta |
| PIX-422-011 | 422 | Limite de chaves por conta excedido |
| PIX-422-013 | 422 | BR Code ilegível, com CRC inválido ou que não é Pix (`POST /v1/payment-orders/decodings`) |
| PIX-422-020 | 422 | Cobrança em estado final / chave inativa |
| PIX-422-030 | 422 | Devolução excede valor devolvível |
| PIX-422-031 | 422 | Fora do prazo de devolução |
| PIX-422-040 | 422 | CPF do favorecido diverge do DICT |
| PIX-422-050 | 422 | Limite Pix excedido (noturno, diário, por transação, dispositivo não cadastrado) |
| PIX-422-052 | 422 | Valor de limite acima do teto do banco |
| PIX-422-060 | 422 | Débito/cobrança de Pix Automático sem autorização `APROVADA` |
| PIX-422-061 | 422 | Agendamento inválido (data passada, recorrência inconsistente) |
| PIX-422-062 | 422 | Cancelamento de agendamento fora do prazo/estado |
| PIX-422-070 | 422 | Contestação MED fora dos 80 dias da devolução |
| PIX-422-071 | 422 | Prazo de abertura de MED (80 dias) excedido |
| PIX-422-051 | 422 | Saldo insuficiente |
| PIX-422-900 | 422 | Idempotency-Key reutilizada com corpo diferente |
| PIX-428-001 | 428 | `If-Match` obrigatório |
| PIX-429-001 | 429 | Rate limit (cliente/DICT) |
| PIX-429-002 | 429 | Excesso de pedidos de código OTP (espera entre pedidos ou teto diário); traz `Retry-After` em segundos |
| PIX-422-014 | 422 | Código OTP incorreto (a mensagem diz quantas tentativas restam) |
| PIX-422-015 | 422 | Código OTP vencido |
| PIX-422-016 | 422 | Tentativas do código OTP esgotadas: peça outro |
| PIX-500-001 | 500 | Erro interno (mensagem fixa) |
| PIX-502-001 | 502 | Falha em dependência (DICT/ledger) — **técnica** |
| PIX-503-001 | 503 | Dependência indisponível / circuito aberto + `Retry-After` |
Regra: **todo** literal `"PIX-…"` fora de `ErrorCodes` falha no CI (`grep`).

### 4.6 Tipos e formatos
- Dinheiro em JSON como **string decimal** (`"123.45"`, padrão BCB `\d{1,10}\.\d{2}`) na superfície BCB; na gestão também string decimal (evita perda de precisão do `number`).
- Datas/hora: RFC 3339 com offset; `date` ISO-8601 só para calendário (vencimento).
- Enums em JSON = valor literal do banco (wire value estável); renomear constante = quebra de contrato (teste de snapshot dos enums).
- **CPF/CNPJ**: entrada com ou sem máscara; CNPJ numérico **ou alfanumérico** (`12.ABC.345/01DE-35`). Saída **mascarada por padrão**; **completa somente** com o escopo OAuth `pii.document.read` no token do cliente (todo acesso completo grava `audit_log`). Campo `document` (gestão) / `cpf`,`cnpj` (compat BCB).

### 4.7 HTTP e cache
`201` com `Location` e `ETag` em criação; `202` só quando o trabalho **não terminou**, com `Location` do recurso de status (ex.: ordem `SENT_TO_SPI`); `Cache-Control: no-store` em respostas com dados financeiros; `Sunset`/`Deprecation` quando algo for descontinuado.

## 5. Validação de CPF na borda
```java
// api/rest: apenas delega ao VO (regra mora no core)
@Target({FIELD, PARAMETER}) @Constraint(validatedBy = CpfConstraintValidator.class)
public @interface ValidCpf { String message() default "{pix.cpf.invalid}"; /* ... */ }
```
Entrada `529.982.247-25` e `52998224725` ⇒ válidas; `111.11111111`, `52998224724`, `""` ⇒ **422** `PIX-422-001`. Para `devedor` na cobrança: `cpf` **xor** `cnpj` (400 se ambos).

## 6. Exemplos

**Criar cobrança imediata (BCB)**
```http
PUT /api/pix/v2/cob/7978c0c97ea847e78e8849634473c1f1
Authorization: Bearer …
Content-Type: application/json

{ "calendario": { "expiracao": 3600 },
  "devedor": { "cpf": "52998224725", "nome": "Maria da Silva" },
  "valor": { "original": "123.45" },
  "chave": "<chave EVP ativa do recebedor>",
  "solicitacaoPagador": "Pedido 1042" }
```
```http
HTTP/1.1 201 Created
Location: /api/pix/v2/cob/7978c0c97ea847e78e8849634473c1f1
ETag: "0"
{ "txid": "7978c0c97ea847e78e8849634473c1f1", "revisao": 0, "status": "ATIVA",
  "calendario": { "criacao": "2026-10-15T14:00:00Z", "expiracao": 3600 },
  "devedor": { "cpf": "***.982.247-**", "nome": "Maria da Silva" },
  "valor": { "original": "123.45" },
  "location": "pix.example.com/qr/v2/cobv/9d36b84f…", "loc": { "id": 789, "tipoCob": "cob" } }
```
> O OpenAPI do BCB devolve o CPF do devedor como informado. **Decisão B8:** mascarado por padrão; o valor completo só é devolvido ao cliente cujo token traz o escopo `pii.document.read` (concedido no IdP).

**Pagamento por chave (gestão)**
```http
POST /v1/payment-orders
Idempotency-Key: 3f6c1c3a-…
{ "payerAccountId": "…", "method": "KEY",
  "destination": { "pixKeyType": "CPF", "pixKey": "52998224725", "documentHint": "52998224725" },
  "amount": "250.00", "payerMessage": "Aluguel" }
→ 201 { "id": "…", "status": "RESERVED", "endToEndId": null }
```

## 6b. Contrato com sistemas vizinhos (rascunho — `10` E2)
| Sistema | Interface | Contrato mínimo |
|---|---|---|
| `dict-gateway` (HTTP) | `GET/POST /dict/v1/entries/lookup`, `POST /dict/v1/entries`, `DELETE`-like via `POST …/deletion`, `claims`, `funds-recoveries/**` | Gateway já aplica **limite, cache e retentativas**; erros: `404/422` negócio, `429` (`Retry-After`), `5xx/timeout` técnico; ms envia `X-Request-Id`/`traceparent` e chave de idempotência em escritas |
| Core/ledger (HTTP) | `POST /ledger/v1/reservations` (`reservar`), `POST …/{endToEndId}/confirmation`, `POST …/{endToEndId}/cancellation`, `GET …/{endToEndId}` | Idempotente por **`endToEndId`** (header `Idempotency-Key` = e2e); respostas: `RESERVED`, `INSUFFICIENT_FUNDS` (negócio), `ACCOUNT_BLOCKED` (negócio), 5xx (técnico) |
| `spi-gateway` (Kafka) | tópicos `pix.spi.*` (`06`) | Avro; chave = `endToEndId` (ou `rtrId`/`idRec`) |
| IdP | JWKS | `iss`, `aud`, `exp`, claim `scope` |

## 7. Documentação (OpenAPI/AsyncAPI)
- REST: `springdoc-openapi` gera `/v3/api-docs`; o contrato versionado em `docs/api` é **comparado** ao gerado (`OpenApiContractTest`) — divergência quebra o build. `@DefaultApiResponse` (meta-anotação) documenta só respostas **realmente produzidas**; 405/415/429 só entram quando houver handler.
- Eventos: Springwolf (`/springwolf/docs`, `/springwolf/asyncapi-ui.html`).

## 8. Backward compatibility (regras de PR)
Quebra: remover/renomear campo, mudar tipo, tornar opcional→obrigatório, renomear constante de enum, trocar array por envelope. Não-quebra: campo opcional novo, endpoint novo, query param opcional. Teste `EnumWireValuesSnapshotTest` e diff do OpenAPI no PR.

## 9. Checklist `api-contract-review` (aplicado)
- [x] Verbos corretos (sem POST p/ leitura salvo `lookups`, justificado por PII; sem GET com efeito)
- [x] Versionado em todos os controllers
- [x] DTOs (records), nunca entidade; CPF mascarado
- [ ] Paginação consistente (✅ envelope único com `totalPages`) e header `Link` (⚠️ não emitido)
- [x] 201 + `Location` + `ETag`; 202 só se assíncrono real
- [x] Problem Details; 405/415/429 tratados
- [x] `If-Match` com 428/412/400; `If-None-Match` com 304
- [x] Idempotency-Key com replay, hash e sem queimar chave
- [x] Dinheiro `BigDecimal`/string; datas com offset
- [x] Códigos só via `ErrorCodes`

## Entrega do webhook (implementada)
- **Quando:** a cada Pix recebido numa chave com webhook. A entrega nasce `PENDING` **na mesma transação** que grava o Pix (a chave é achada pelo índice cego informado pelo SPI ou pela chave da cobrança quitada). Única por (assinatura, Pix): reprocessar a mensagem não duplica. Um erro ao enfileirar **nunca** impede o crédito.
- **Requisição:** `POST <webhookUrl>/pix`, `Content-Type: application/json`, corpo da API Pix: `{"pix":[{"endToEndId","txid"?,"chave","valor","horario","infoPagador"?}]}` — **sem dados do pagador**. Sem redirecionamento (um 30x é falha), timeout de conexão 3 s e de resposta 5 s; só o status importa.
- **Autenticidade (receptor):** `x-pix-timestamp` (epoch s) e `x-pix-signature: v1=<hex>` onde `hex = HMAC-SHA256(PIX_WEBHOOK_SIGNING_KEY, timestamp + "." + corpo)`. O receptor recalcula, compara em tempo constante e **rejeita timestamp com mais de 5 min** (anti-replay). A chave é compartilhada por contrato com o cliente (rotacioná-la exige combinar com todos).
- **Resultado:** **2xx = `DELIVERED`**; qualquer outra coisa (3xx/4xx/5xx, timeout, DNS, URL que passou a ser bloqueada) = `RETRYING` com backoff exponencial ×6 e jitter de 20% (5 s, 30 s, 3 min, 18 min, 1h48, 6 h…) e, após **8 tentativas**, `DEAD` (reenvio manual por back-office). O receptor precisa ser **idempotente** (mesmo `endToEndId` pode chegar mais de uma vez).
- **Métricas/alertas:** `pix_webhook_deliveries_dead`, `pix_webhook_deliveries_overdue`; runbook `docs/runbooks/webhook-entregas.md`.
- **Limites conhecidos:** envio sequencial por lote (20 a cada 2 s: um receptor lento atrasa os demais); a checagem anti-SSRF resolve o DNS e a conexão resolve de novo (janela de DNS rebinding reduzida por re-checar a cada envio e não seguir redirecionamentos; fechamento total exige IP fixado ou egress proxy); mTLS com o receptor não implementado.

## Pagar QR dinâmico (implementado)
`POST /v1/payment-orders` com `method: QR_DYNAMIC` e `qrPayload` (BR Code com a URL do payload na tag 26/25). O ms **busca** `https://<url>` (Accept `application/jose`), **valida** e paga o **valor e a chave do payload**, nunca os digitados (se o cliente enviar `amount`, precisa ser igual).
- **Defesas:** URL é entrada do usuário ⇒ política anti-SSRF (só https, host público, sem credencial na URL; checada antes de conectar), sem seguir redirecionamentos, corpo ≤ 64 KB, timeouts 3 s/5 s.
- **Validação do JWS:** algoritmo RS256/PS256/ES256 (HS* e `none` recusados); cadeia `x5c` obrigatória; assinatura conferida com o certificado folha; cadeia validada contra a trust store `PIX_QR_TRUST_STORE_PATH` (ICP-Brasil). **Sem trust store o ms recusa o QR dinâmico (falha fechada)**. Revogação (CRL/OCSP) **não** é verificada.
- **Regras:** `status` precisa ser `ATIVA`; vencida (cob: criação + expiração; cobv: fim do dia do vencimento + `validadeAposVencimento`, fuso de Brasília) ⇒ recusa. Todas as recusas do payload usam **422 `PIX-422-018`**; recebedor fora do ar/lento ⇒ 503.

## Pix Agendado — execução
Worker (ShedLock, 30 s) reivindica ocorrências vencidas com `FOR UPDATE SKIP LOCKED`, cria a `payment_order` (`origin=SCHEDULED`) pelo mesmo caminho do pagamento imediato (DICT fora de transação; limite + reserva + ordem + vínculo na mesma) e acompanha o desfecho. `SENT_TO_SPI`/`SETTLED` ⇒ `EXECUTED`. **Saldo insuficiente (`AM04`)**: nova tentativa no mesmo dia entre 18h e 21h com aviso ao cliente (evento `ScheduledOccurrenceRetryNotified` em `pix.schedule.v1`); persistindo ⇒ `FAILED_INSUFFICIENT_FUNDS`. Falha de negócio (chave inexistente, limite) ⇒ `FAILED` sem retry; técnica (DICT fora) ⇒ volta a aguardar com backoff (5 tentativas); a falha de uma ocorrência **não** cancela as seguintes. Sistema parado além de `max-late-days` (3) ⇒ `FAILED(MISSED)`: não paga fora de hora. Datas mensais saem da data inicial (31/01 → 28/02 → 31/03).

## Pix Automático — execução (pagador)
Cobrança aceita ⇒ tentativa `AGND` na data de vencimento. O worker (30 s) cria a ordem `origin=AUTOMATIC` (conta do recebedor vem na cobrança). Pago ⇒ tentativa `PAGA` e cobrança `CONCLUIDA`. Saldo insuficiente: com `allow_retry` (2º caractere do `idRec` = `R`) cria `NTAG` no dia seguinte até `retry-days` (3); sem ⇒ cobrança `REJEITADA`. Autorização cancelada, teto reduzido abaixo do valor ou vencimento além de `max-late-days` ⇒ não debita (`PIX-422-060` no log). **Antecedência da cobrança (2–10 dias), dias de nova tentativa e validade da solicitação são parâmetros de projeto NÃO confirmados no regulamento** (`docs/regulatory-verification.md`). Respostas ao recebedor (aceite/rejeição/cancelamento) saem pelo gateway (contrato rascunho: `POST /spi/v1/automatic/authorizations/{idRec}/approval|rejection|cancellation`).

## MED — fluxo implementado
REPORTED (nosso cliente recebeu): `OPEN` → `acceptance` (reserva `MED_BLOCK` no core; recusa do core deixa o caso `ACCEPTED` sem valor bloqueado e registra o motivo) → `refund` (cancela o bloqueio e pede a devolução MED) → o acompanhamento fecha em `CLOSED` quando a devolução é `DEVOLVIDO` (ou volta a `ACCEPTED` se `NAO_REALIZADO`) → contestação (80 dias da devolução) aceita ⇒ `CONTESTED`. REPORTER (nosso cliente pagou): só pagamento `SETTLED` ≤ 80 dias; o ms notifica o DICT pelo gateway (contrato rascunho: `POST /dict/v1/funds-recoveries`, `…/{id}/cancellation`) e guarda o id; `closure` registra o resultado. Toda transição grava histórico append-only e evento em `pix.med.v1`. **Não implementado:** entrada da notificação por Kafka (`pix.spi.med-notification.v1`), rastreamento automático em camadas (`med_hop`) e bloqueio parcial conforme entra saldo.

