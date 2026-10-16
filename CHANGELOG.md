# Changelog
Formato [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/) · versionamento [SemVer](https://semver.org/lang/pt-BR/).

## [Unreleased]

## [0.1.0] - 2026-10-16
Primeira versão versionada do ms-pix-manager.

### Adicionado
- Pix Agendado, Pix Automático e casos de MED (`feat(pix)`).
- Webhook de Pix recebido: exige https, valida TLS e restringe o reenvio ao escopo `webhook.admin` (`feat(webhook)`).
- Métricas e alertas de entregas de webhook DEAD e vencidas (`feat(ops)`).
- Receptor de webhook fictício em https que valida a assinatura, nos mocks.
- Gates de qualidade: JaCoCo com 85% de linhas por pacote do core e 65% de branches global.
- CI: gitleaks, dependency-review, pre-commit, e disparo em `develop`, `release/**` e `hotfix/**`.
- Governança: git flow, labels `type/*` `priority/*` `status/*` `area/*`, rotulagem automática de PRs, templates de issue/PR de release e segurança.

### Alterado
- Collection do Postman, `validate-api` e `demo-flow` cobrem o webhook e os novos fluxos.
- Notas de release agrupadas por `type/*`; Dependabot aponta para `develop`.

[Unreleased]: https://github.com/rodrigorrd-dev/ms-pix-manager/compare/v0.1.0...develop
[0.1.0]: https://github.com/rodrigorrd-dev/ms-pix-manager/releases/tag/v0.1.0
