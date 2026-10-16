## O que muda e por quê
<!-- Link da issue: Closes #123 -->

## Tipo
- [ ] feat · [ ] fix · [ ] perf · [ ] refactor · [ ] test · [ ] docs · [ ] chore · [ ] ci
<!-- Os labels type/* e area/* são aplicados automaticamente (branch e arquivos). Quebra de contrato: acrescente `type/breaking-change` e `!` no título. -->

## Checklist
- [ ] Spec (FR/BR em `docs/02`) e contrato (OpenAPI/Avro/DDL) atualizados **no mesmo PR** quando o comportamento muda
- [ ] Teste escrito a partir do cenário; `./mvn.sh verify` verde
- [ ] Migração Liquibase nova é aditiva e compatível com a versão anterior do app (rollout sem downtime)
- [ ] Sem PII (CPF/CNPJ/chave Pix) em log, teste, fixture ou descrição deste PR
- [ ] Sem segredo no diff (`.env` não é versionado)
- [ ] Mudança de performance vem com medição (Gatling/JMH) antes e depois
- [ ] Título no padrão Conventional Commits (`feat(charge): ...`), pois vira o commit do squash e o changelog
