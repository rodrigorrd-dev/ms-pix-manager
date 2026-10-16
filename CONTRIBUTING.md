# Como contribuir

## Modelo de branches: trunk-based
- `main` é sempre implantável e **protegida** (sem push direto).
- Todo trabalho vem de uma **issue** e vive numa branch curta (poucos dias): `feat/123-lista-cobrancas`, `fix/145-timeout-ledger`, `chore/...`.
- Entrega por **Pull Request** com *squash merge*: o título do PR (Conventional Commits) vira o commit na `main`.
- Sem `develop`/`release/*`. Mudança incompleta entra atrás de configuração, não numa branch longa.

## Fluxo
1. Abra/escolha uma issue (templates em `.github/ISSUE_TEMPLATE`). Mudança de comportamento começa pela **spec** (`docs/02`) e pelo contrato.
2. Crie a branch a partir da `main` atualizada.
3. Escreva o teste a partir do cenário; rode `./mvn.sh verify` (gate de cobertura incluso).
4. Abra o PR preenchendo o checklist, com `Closes #<issue>`. O CI (`ci`, `pr-title`) precisa passar e é exigida 1 aprovação (CODEOWNERS).
5. Squash merge. A branch é apagada.

## Commits e PRs
`tipo(escopo): descrição` em minúsculas, no imperativo. Tipos: `feat fix perf refactor test docs chore ci build revert`; `!` ou `BREAKING CHANGE:` no rodapé para quebra de contrato.
Não inclua PII, segredos nem trailers de coautoria automáticos nos commits e PRs.

## Versionamento e release
SemVer por tag anotada: `git tag -a v1.2.0 -m "v1.2.0" && git push origin v1.2.0`. A tag dispara a imagem versionada no GHCR e a GitHub Release com notas dos PRs. Correção urgente: PR direto na `main` e nova tag de patch.

## Branch protegida (configurar em Settings → Branches → `main`)
Require a pull request (1 aprovação, *code owners*), require status checks (`build-test`, `security`, `title`), branch atualizada antes do merge, histórico linear, bloquear force-push e exclusão, só squash merge, apagar branches após o merge.
