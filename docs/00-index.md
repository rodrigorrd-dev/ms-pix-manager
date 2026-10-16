# 00 — Índice e guia de leitura

## Ordem de leitura por papel
| Papel | Leia nesta ordem |
|---|---|
| Produto / negócio | `10` → `02` → `09` |
| Arquiteto | `01` → `03` → `06` → `07` |
| Backend | `02` → `04` → `05` → `06` → `08` |
| QA | `02` (Gherkin) → `08` |
| Segurança / DPO | `07` §3 → `04` §3.4 → `10` (B7) |
| SRE | `07` → `06` §7 → `docker/` |
| Todos (nomes) | `11` (glossário em inglês) |

## Glossário mínimo
`PSP` participante · `ISPB` id do participante (8 dígitos) · `DICT` diretório de chaves · `SPI` liquidação instantânea · `e2eId` EndToEndId (32) · `txid` id de conciliação (26–35 na cobrança) · `cob/cobv/loc` cobrança imediata / com vencimento / location · `MED` mecanismo especial de devolução · `CID` content identifier (reconciliação DICT) · `Outbox/Inbox` tabelas de consistência de mensageria · `DLT` dead-letter topic.

## Convenções de ID
`FR-*` requisito funcional · `BR-*` regra de negócio · `NFR-*` não funcional · `ADR-nnn` decisão de arquitetura · `PIX-ddd-nnn` código de erro · `A1…`, `B1…` perguntas em aberto.

## Skills aplicadas (resumo) → ver `01` §6.2
rfc-writing · architecture-conventions · architecture-review · c4-model · solid-principles · design-patterns · clean-code · code-design · safe-refactoring · spring-boot-patterns · jpa-patterns · cqrs-architecture · api-contract-review · resilience-patterns · cache-patterns · concurrency-review · security-audit · logging-patterns · test-pyramid · test-quality · performance-testing · performance-smell-detection · java-migration · maven-dependency-audit · java-code-review · git-commit · changelog-generator · issue-triage.
