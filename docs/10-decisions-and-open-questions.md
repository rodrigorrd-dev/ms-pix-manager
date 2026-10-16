# 10 — Decisões (fechadas) e pendências

> Convenção RFC 2119: **MUST** obrigatório · **SHOULD** recomendado · **MAY** opcional.
> Rodada 1 de respostas: 2026-10-15. As afirmações regulatórias abaixo foram **informadas pelo solicitante**; cada uma indica se também foi confirmada por busca (✅ fonte secundária, ⚠️ não verificada). Fontes oficiais ainda devem ser conferidas antes de fixar valores no código (ver `09` §4).

## 1. Decisões fechadas

| ID | Decisão | Onde está refletida |
|---|---|---|
| A1 | PSP **direto** (ISPB próprio). `spi-gateway` traduz pacs.* ⇄ Kafka; o ms **nunca** fala com o SPI. DICT via HTTP **somente** pelo **`dict-gateway`**, que centraliza limite de consultas, cache e retentativas | `03` §1, `07` §2, `06` |
| A2 | Saldo/ledger no **core bancário**; o ms guarda só o estado Pix. API do core idempotente: **reservar → confirmar \| cancelar**, chave de idempotência = **`endToEndId`** | `04` (`ledger_reservation`), `02` BR-PAY-02 |
| A3 | Instituição única; **ISPB em configuração** e carregado nos eventos (`ispb` no envelope), nunca fixo no código | `03` §3, `06` §3 |
| B1 | **CPF e CNPJ** com dígito verificador; CNPJ **numérico e alfanumérico** (módulo 11 com valor = ASCII − 48; DV numérico). Origem do formato: **Receita Federal** (não BCB); a API Pix aceita `^[0-9A-Z]{14}$`. Algoritmo conferido contra o exemplo oficial `12.ABC.345/01DE-35` ✅ | `02` FR-DOC, BR-DOC |
| B2 | Noturno R$ 1.000 **PF** 20h–6h (PJ isenta); **aumento** só vale após **mín. 24 h / máx. 48 h**, **redução imediata**; dispositivo não cadastrado: **R$ 200/tx e R$ 1.000/dia**; diurnos R$ 5.000 PF / R$ 50.000 PJ = política interna configurável, cliente altera até o **teto do banco** | `02` BR-LIM, `04` (`pix_limit`) |
| B3 | **Pix Agendado** (único e recorrente) e **Pix Automático (lado pagador)** entram na **v1**. Saque/Troco → backlog | `02` §3.10–3.11, `04`, `05`, `06` |
| B4 | **MED 2.0**: registra infração, bloqueio cautelar no ledger, eventos, **rastreamento em múltiplas camadas** com bloqueios/devoluções parciais conforme entra saldo, dentro do prazo | `02` BR-MED, `04` (`med_hop`) |
| B5 | BR Code estático e dinâmico na v1 | `02` FR-PAY-01 |
| B6 | Webhook: 8 tentativas, backoff exponencial + jitter 5 s–6 h → `DEAD` com **reenvio manual** (decisão interna) | `02` BR-WHK, `05` |
| B7 | Retenção ≥ 5 anos; PII criptografada; **crypto-shredding no backlog e só após o prazo legal** (exclusão LGPD não prevalece sobre retenção obrigatória) | `04` §3.4/3.7 |
| B8 | CPF **mascarado por padrão**; completo **apenas** para cliente OAuth com **escopo específico** (`pii.document.read`) | `05` §4.6, `07` §3 |
| C1 | `br.com.pixmanager` (placeholder). **Pacotes por módulo de negócio na raiz** (`charge`, `refund`, `reconciliation`, …) com **hexagonal dentro de cada módulo** (`domain`, `application`, `adapter`) | `03` §2 (reescrito) |
| C2 | ms = **OAuth2 Resource Server** (JWT: assinatura via JWKS, `iss`, `aud`, `exp`); autoriza por escopos da API Pix (`cob.*`, `cobv.*`, `pix.*`, `webhook.*`, `payloadlocation.*`); **não emite token nem cadastra cliente**; mTLS terminado no gateway; **Keycloak em container/Testcontainers** em dev/teste | `07` §3, `08` |
| C3 | OTLP → OTel Collector; **Prometheus** (métricas), **Tempo** (traces), **Loki** (logs), Grafana | `07` §4, `docker/` |
| C4 | Containers 12-factor; **GitHub Actions**: `mvn verify` (+JaCoCo), **OWASP dependency-check**, **Trivy** na imagem; deploy **Docker Compose em VPS**; manifestos K8s/Helm opcionais | `07` §1/§7, `README` |
| C5 | **Sem Redis na v1**; idempotência/inbox/outbox/locks no PostgreSQL (`SKIP LOCKED`/ShedLock). Redis só depois, como cache de consultas DICT (hoje o cache fica no dict-gateway) | `03` ADR-011 |
| D1 | MED: **80 dias** (abertura); análise até **7 dias**; **contestação pelo recebedor: 80 dias da devolução, vigente desde 2026-09-01** (antes 30) ✅; devolução comum **90 dias**; reivindicação: **30 dias** para validar chave. Doador **7 dias** ⚠️ (Manual de Tempos do Pix **não verificado**) | `02`, `09` |
| D2 | Noturno R$ 1.000 PF 20h–6h; chaves **PF 5 / PJ 20 por conta** (limite **por conta**, não por pessoa) | `02` BR-KEY-03 |
| D3 | Manual de Padrões **2.10.0** (IN BCB 769, 19/08/2026; revoga IN 658/2.9.0; inclui iniciação `AUTO` e ajustes de recorrência / QR Code Composto). **Manual do DICT 8.5** (jul/2026) | `09` |

## 2. Pontos em que a busca **não** confirmou integralmente (tratar como premissa até checar na fonte)
1. **CNPJ alfanumérico — "primeiros emitidos a partir de jul/2026"**: algoritmo e coexistência dos formatos ✅ (várias fontes); data de 1ª emissão só em **uma** fonte (31/07/2026).
2. **MED 2.0 "obrigatório desde fev/2026"**: o BCB informa **MED 2.0 em produção desde 11/05/2026**; imprensa de nov/2025 previa adesão obrigatória em 02/02/2026; **não achei confirmação de que essa obrigatoriedade passou a valer** ⇒ a spec implementa o MED 2.0 (como você decidiu), mas a data legal fica ⚠️. Há ainda **ajuste de camada no encaminhamento da notificação com etapa operacional em 26/10/2026** (BCB) — vira requisito de campo `layer` na notificação ✅ modelado.
3. **Contestação de 80 dias (IN BCB 766, 01/09/2026)** ✅ confirmada.
4. **Pix Agendado recorrente obrigatório desde out/2024; Pix Automático de oferta obrigatória; retry 18h–21h**: ⚠️ não verifiquei o texto do Regulamento.

## 3. Pendências abertas (preciso da sua resposta)

### Bloqueantes de modelagem/código
- [ ] **E1. Spring Boot:** seguir com **3.5.x** (última 3.x; confirmar suporte OSS) ou já **4.x**? *(Suposição: 3.5.x conforme "Spring Boot 3".)*
- [ ] **E2. Contratos dos gateways (ms ⇄ `spi-gateway`, `dict-gateway`, core):** esses componentes já existem com contrato definido? Se sim, envie; se não, a spec **define** o contrato (rascunho em `06` §10 e `05` §11). *(Suposição: definimos aqui e eles se adaptam.)*
- [ ] **E3. Pix Automático — regras de tentativa** (janela de envio da cobrança antes do vencimento, nº e datas das retentativas pós-vencimento, prazos de aceite/expiração da solicitação, valor máximo do pagador): me passe o trecho do Manual de Padrões 2.10.0 / Regulamento ou confirme que posso usar o do OpenAPI (`AGND`/`NTAG`/`RIFL`, `allowRetry` pelo 2º caractere do `idRec`). *(Suposição: parâmetros em `PixPolicyProperties` com padrões conservadores e **marcados [R]**.)*
- [ ] **E4. Pix Agendado — regras:** cancelamento até o dia anterior? limite de agendamento no futuro? qual limite vale (do dia da criação ou da execução)? quais frequências recorrentes são obrigatórias? *(Suposição: cancela até 23:59 do dia anterior; limite avaliado **na execução**; frequências do enum de `scheduled_payment`.)*
- [ ] **E5. Prazo de resolução do doador (7 dias)** — confirmar no Manual de Tempos do Pix.

### Decisões de projeto que sua resposta C1/C4 tornou arriscadas — preciso do seu "ok"
- [x] **F1. Idioma — FECHADO (2026-10-15):** **toda a nomenclatura em inglês** (módulos, pacotes, classes, métodos, colunas, campos próprios, tópicos, métricas, IDs de requisito `FR/BR/NFR`), seguindo `architecture-conventions`; termos regulatórios do BCB/Receita ficam literais. Prosa da documentação permanece em pt-BR. Ver [`11-naming-glossary.md`](11-naming-glossary.md).
- [ ] **F2. SLO × infraestrutura:** **99,95% (Pix 24x7)** não é alcançável com **um único VPS com Docker Compose** (uma queda de host derruba tudo; 99,95% ≈ 22 min/mês). Mantive a meta no `07` como **alvo de produção** e adicionei um **perfil "VPS/single-node"** com SLO realista (**99,5%**) + riscos. Para PSP direto/ISPB próprio, o BCB exige continuidade e contingência — confirmar se a VPS é só **hml/piloto** e o prod terá HA (≥ 2 nós, PG com réplica, Kafka 3 brokers).
- [ ] **F3. Escopo `pii.document.read`** para ver CPF completo: quem concede (IdP) e como é auditado? *(Suposição: concedido no IdP por cliente; todo acesso a documento completo gera linha em `audit_log`.)*

## 4. Já resolvido por sua resposta — itens removidos da lista anterior
A1–A3, B1–B8, C1–C5, D1–D3 (exceto o doador de 7 dias, E5).
