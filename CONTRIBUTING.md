# Como contribuir

## Modelo de branches: trunk-based
- `main` é sempre implantável e **protegida** (sem push direto).
- Todo trabalho vem de uma **issue** e vive numa branch curta (poucos dias): `feat/123-lista-cobrancas`, `fix/145-timeout-ledger`, `chore/...`.
- Entrega por **Pull Request** com *squash merge*: o título do PR (Conventional Commits) vira o commit na `main`.
- Sem `develop`/`release/*`. Mudança incompleta entra atrás de configuração, não numa branch longa. Se a equipe preferir o gitflow clássico, veja "Alternativa: gitflow" abaixo.

## Fluxo
1. Abra/escolha uma issue (templates em `.github/ISSUE_TEMPLATE`). Mudança de comportamento começa pela **spec** (`docs/02`) e pelo contrato.
2. Crie a branch a partir da `main` atualizada.
3. Escreva o teste a partir do cenário; rode `./mvn.sh verify` (gate de cobertura incluso).
4. Abra o PR preenchendo o checklist, com `Closes #<issue>`. O CI (`ci`, `pr-title`) precisa passar e é exigida 1 aprovação (CODEOWNERS).
5. Squash merge. A branch é apagada.

## Commits e PRs
`tipo(escopo): descrição` em minúsculas, no imperativo. Tipos: `feat fix perf refactor test docs chore ci build revert`; `!` ou `BREAKING CHANGE:` no rodapé para quebra de contrato.
Não inclua PII, segredos nem trailers de coautoria automáticos nos commits e PRs.

## Comandos: do commit ao PR (fluxo atual, a partir da `main`)
```bash
# 1. branch curta a partir da main atualizada
git switch main && git pull --ff-only origin main
git switch -c feat/123-lista-cobrancas

# 2. trabalhe, rode os testes e confira o que vai entrar
./mvn.sh verify
git status && git diff

# 3. adicione só o que pertence a esta mudança (evite `git add -A` às cegas) e commite
git add src/main/java/... src/test/java/... docs/02-requirements-business-rules.md
git commit -m "feat(charge): lista cobranças com filtro por status" -m "Closes #123"

# 4. mantenha a branch em dia com a main (rebase, histórico linear)
git fetch origin && git rebase origin/main

# 5. publique e abra o PR (título no padrão Conventional Commits)
git push -u origin feat/123-lista-cobrancas
gh pr create --title "feat(charge): lista cobranças com filtro por status" --body "Closes #123"

# 6. depois do squash merge
git switch main && git pull --ff-only origin main
git branch -d feat/123-lista-cobrancas
```
Ajustes após a revisão: novo commit na mesma branch e `git push` (o squash junta tudo). Depois de um `rebase`, use `git push --force-with-lease` (nunca `--force`).

Release: `git switch main && git pull --ff-only && git tag -a v1.2.0 -m "v1.2.0" && git push origin v1.2.0`.

## Alternativa: gitflow
Use só se houver mais de uma versão em produção ao mesmo tempo. Exige ajustes: o `ci.yml` hoje dispara `push` apenas na `main` (inclua `develop`, `release/**` e `hotfix/**`) e a proteção de branch vale para `main` **e** `develop`.

| Branch | Origem | Volta para | Nome |
|---|---|---|---|
| `main` | | | só código em produção, cada merge recebe uma tag |
| `develop` | | | integração da próxima versão |
| `feature/*` | `develop` | `develop` | `feature/123-lista-cobrancas` |
| `release/*` | `develop` | `main` e `develop` | `release/1.2.0` |
| `hotfix/*` | `main` | `main` e `develop` | `hotfix/1.2.1-timeout-ledger` |

```bash
# feature
git switch develop && git pull --ff-only origin develop
git switch -c feature/123-lista-cobrancas
git add <arquivos> && git commit -m "feat(charge): lista cobranças com filtro por status" -m "Closes #123"
git push -u origin feature/123-lista-cobrancas      # PR para develop (squash)

# release: congela a versão, só correções entram aqui
git switch develop && git pull --ff-only origin develop
git switch -c release/1.2.0
git push -u origin release/1.2.0                    # PR para main (merge commit)
git switch main && git pull --ff-only origin main
git tag -a v1.2.0 -m "v1.2.0" && git push origin v1.2.0
git switch develop && git merge --no-ff main        # traz as correções da release de volta
git push origin develop && git push origin --delete release/1.2.0

# hotfix: correção urgente em produção
git switch main && git pull --ff-only origin main
git switch -c hotfix/1.2.1-timeout-ledger
git add <arquivos> && git commit -m "fix(ledger): respeita o timeout de leitura"
git push -u origin hotfix/1.2.1-timeout-ledger      # PR para main
git tag -a v1.2.1 -m "v1.2.1" && git push origin v1.2.1   # depois do merge, na main
git switch develop && git merge --no-ff main && git push origin develop
```
No gitflow o squash vale só para `feature/*`; `release/*` e `hotfix/*` entram com merge commit, para preservar o histórico entre `main` e `develop`.

## Versionamento e release
SemVer por tag anotada: `git tag -a v1.2.0 -m "v1.2.0" && git push origin v1.2.0`. A tag dispara a imagem versionada no GHCR e a GitHub Release com notas dos PRs. Correção urgente: PR direto na `main` e nova tag de patch.

## Branch protegida (configurar em Settings → Branches → `main`)
Require a pull request (1 aprovação, *code owners*), require status checks (`build-test`, `security`, `title`), branch atualizada antes do merge, histórico linear, bloquear force-push e exclusão, só squash merge, apagar branches após o merge.
