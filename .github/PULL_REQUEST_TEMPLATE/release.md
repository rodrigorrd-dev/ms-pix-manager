## Release vX.Y.Z
<!-- PR de release/* ou hotfix/* para main: merge commit, sem squash. Título: `chore(release): X.Y.Z` -->

## Conteúdo
<!-- Cole as notas do CHANGELOG.md desta versão -->

## Checklist
- [ ] Versão do `pom.xml` e `CHANGELOG.md` atualizados
- [ ] `./mvn.sh verify` e CI verdes
- [ ] Migrações Liquibase revisadas (aditivas, com rollback)
- [ ] Quebras de contrato (API/evento/schema) listadas no CHANGELOG
- [ ] Depois do merge: tag `vX.Y.Z` em `main` e merge de `main` em `develop`
