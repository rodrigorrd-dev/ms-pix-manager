# Como contribuir

## Modelo de branches: git flow
| Branch | Origem | Volta para | Nome |
|---|---|---|---|
| `main` | | | só código em produção; cada merge recebe uma tag `vX.Y.Z` |
| `develop` | `main` | | integração da próxima versão; é a base de todo trabalho |
| `feature/*` | `develop` | `develop` | `feature/123-lista-cobrancas` |
| `release/*` | `develop` | `main` e `develop` | `release/1.2.0` |
| `hotfix/*` | `main` | `main` e `develop` | `hotfix/1.2.1-timeout-ledger` |

- `main` e `develop` são **protegidas** (sem push direto, só por Pull Request).
- `feature/*`, `release/*` e `hotfix/*` são temporárias e apagadas após o merge.
- `release/*` e `hotfix/*` só existem quando há uma versão a publicar ou um incidente em produção. Fora disso o repositório tem apenas `main` e `develop`.
- Merge: `feature/*` entra em `develop` com **squash** (o título do PR vira o commit). `release/*` e `hotfix/*` entram com **merge commit** (`--no-ff`), para preservar o histórico entre `main` e `develop`.
- Mudança incompleta entra atrás de configuração (feature flag), não numa feature branch longa.

## Fluxo
1. Abra/escolha uma issue (templates em `.github/ISSUE_TEMPLATE`). Mudança de comportamento começa pela **spec** (`docs/02`) e pelo contrato.
2. Crie a `feature/*` a partir da `develop` atualizada.
3. Escreva o teste a partir do cenário; rode `./mvn.sh verify` (gates de cobertura inclusos).
4. Abra o PR para `develop` preenchendo o checklist, com `Closes #<issue>`. O CI precisa passar e é exigida 1 aprovação (CODEOWNERS).
5. Squash merge. A branch é apagada.

## Commits e PRs
`tipo(escopo): descrição` em minúsculas, no imperativo. Tipos: `feat fix perf refactor test docs chore ci build revert`; `!` ou `BREAKING CHANGE:` no rodapé para quebra de contrato.
O `pr-title` valida o título de todo PR, inclusive os de release e hotfix (`chore(release): 1.2.0`, `fix(ledger): ...`).
Não inclua PII, segredos nem trailers de coautoria automáticos nos commits e PRs.

Hook local (opcional, recomendado): `pip install pre-commit && pre-commit install --hook-type pre-commit --hook-type commit-msg`. Roda gitleaks, higiene de arquivos e valida a mensagem do commit.

## Comandos

### Feature
```bash
git switch develop && git pull --ff-only origin develop
git switch -c feature/123-lista-cobrancas

./mvn.sh verify
git status && git diff

# adicione só o que pertence a esta mudança (evite `git add -A` às cegas)
git add src/main/java/... src/test/java/... docs/02-requirements-business-rules.md
git commit -m "feat(charge): lista cobranças com filtro por status" -m "Closes #123"

git fetch origin && git rebase origin/develop       # mantém em dia, histórico linear
git push -u origin feature/123-lista-cobrancas
gh pr create --base develop --title "feat(charge): lista cobranças com filtro por status" --body "Closes #123"

# depois do squash merge
git switch develop && git pull --ff-only origin develop
git branch -d feature/123-lista-cobrancas
```
Ajustes após a revisão: novo commit na mesma branch e `git push`. Depois de um `rebase`, use `git push --force-with-lease` (nunca `--force`).

### Release
Congela a versão; só correções entram na `release/*`.
```bash
git switch develop && git pull --ff-only origin develop
git switch -c release/1.2.0
git push -u origin release/1.2.0
gh pr create --base main --title "chore(release): 1.2.0" --body "Release 1.2.0"   # merge commit

# depois do merge do PR
git switch main && git pull --ff-only origin main
git tag -a v1.2.0 -m "v1.2.0" && git push origin v1.2.0
git switch develop && git pull --ff-only origin develop
git merge --no-ff main                               # traz as correções da release de volta
git push origin develop && git push origin --delete release/1.2.0
git branch -d release/1.2.0
```

### Hotfix
Correção urgente em produção.
```bash
git switch main && git pull --ff-only origin main
git switch -c hotfix/1.2.1-timeout-ledger
git add <arquivos> && git commit -m "fix(ledger): respeita o timeout de leitura"
git push -u origin hotfix/1.2.1-timeout-ledger
gh pr create --base main --title "fix(ledger): respeita o timeout de leitura" --body "Hotfix 1.2.1"   # merge commit

# depois do merge do PR
git switch main && git pull --ff-only origin main
git tag -a v1.2.1 -m "v1.2.1" && git push origin v1.2.1
git switch develop && git pull --ff-only origin develop
git merge --no-ff main && git push origin develop
git push origin --delete hotfix/1.2.1-timeout-ledger && git branch -d hotfix/1.2.1-timeout-ledger
```

## Labels: tipo, prioridade, área e status
Definidos em `.github/labels.yml` (sincronizados pelo workflow `labels`). Toda issue e todo PR tem **um** `type/*`; issues têm também `priority/*` e `status/*`; `area/*` é opcional e múltiplo.

| Prefixo | Valores | Quem aplica |
|---|---|---|
| `type/*` | `feature` `bug` `hotfix` `release` `refactor` `perf` `test` `docs` `chore` `ci` `security` `dependencies` `breaking-change` | template da issue; no PR, automático pela branch (`feature/*` → `type/feature`, `hotfix/*`, `release/*`, `dependabot/*`) e pelos arquivos (docs, ci, test) |
| `priority/*` | `p0-critical` `p1-high` `p2-medium` `p3-low` | triagem |
| `status/*` | `triage` `needs-info` `ready` `in-progress` `blocked` `wontfix` | triagem e autor |
| `area/*` | `charge` `pix-key` `payment` `refund` `schedule` `automatic` `med` `webhook` `ledger` `limit` `db` `api` `events` `security` `ops` `docs` | automático pelos arquivos do PR |
| `skip-changelog` | | exclui o PR das notas de release |

Tipo da issue → templates: **Bug**, **Funcionalidade**, **Tarefa técnica**, **Segurança (não sensível)** e **Release**. Bug em produção com impacto: `priority/p0-critical` e branch `hotfix/*`.
As notas da GitHub Release são agrupadas por `type/*` (`.github/release.yml`).

## Tags e versões
- SemVer anotada em `main`: `vMAJOR.MINOR.PATCH` (ex.: `v1.2.0`). Pré-release: `v1.3.0-rc.1`.
- MAJOR: quebra de contrato (API, evento, schema) · MINOR: funcionalidade compatível · PATCH: correção.
- Na `develop` o `pom.xml` fica em `X.Y.0-SNAPSHOT`; a `release/*` remove o `-SNAPSHOT`; depois do merge a `develop` sobe para o próximo `-SNAPSHOT`.
- `CHANGELOG.md` (Keep a Changelog) é atualizado na `release/*`.

## Versionamento e release
SemVer por tag anotada em `main` (`vX.Y.Z`). A tag dispara a imagem versionada no GHCR e a GitHub Release com as notas dos PRs (categorias em `.github/release.yml`).
O CI roda em PRs e em push nas branches `main`, `develop`, `release/**` e `hotfix/**`.

## Branches protegidas (Settings → Branches, para `main` **e** `develop`)
Require a pull request (1 aprovação, *code owners*), require status checks (`build-test`, `security`, `secrets`, `title`), branch atualizada antes do merge, bloquear force-push e exclusão, apagar branches após o merge. Em `develop` permita squash; em `main` permita só merge commit.

## Wiki
A Wiki do GitHub é **gerada** de `README.md`, `subida-producao.md` e `docs/**/*.md` por `scripts/build-wiki.py`, via `.github/workflows/wiki.yml`, a cada push na `main`. Não edite a Wiki direto: a edição é sobrescrita. Para pré-visualizar: `python3 scripts/build-wiki.py /tmp/wiki`.

## Issues automáticas e quadro
- PR com título `feat`, `fix` ou `perf` e sem `Closes #N` ganha uma issue criada pelo workflow `issue-from-pr` (a issue é vinculada no corpo do PR e fecha no merge). Para controlar a issue, abra-a antes e escreva `Closes #N` no PR.
- O quadro (GitHub Projects) se move sozinho pelas automações do próprio projeto: item novo entra em **Aberto**; PR/issue fechado ou mergeado vai para **Resolvido** (*Projects → Workflows*).
