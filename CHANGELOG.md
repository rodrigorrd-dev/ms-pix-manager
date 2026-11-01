# Changelog
Formato [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/) · versionamento [SemVer](https://semver.org/lang/pt-BR/).

## [0.2.2](https://github.com/rodrigorrd-dev/ms-pix-manager/compare/v0.2.1...v0.2.2) (2026-10-23)


### Desempenho

* **metrics:** limita a contagem da outbox_event ([ec84ddc](https://github.com/rodrigorrd-dev/ms-pix-manager/commit/ec84ddc020e9df111e4bfa62b384ff1905bacf1a))
* **metrics:** limita a contagem da outbox_event ([84ab35c](https://github.com/rodrigorrd-dev/ms-pix-manager/commit/84ab35c936e5dc1aa007569c8c1cf192236a7afb))
* **persistence:** cancela cobranças e ocorrências em lote ([5232877](https://github.com/rodrigorrd-dev/ms-pix-manager/commit/5232877e89ec5b4bcf1f075fbaaf243cbff1b463))
* **persistence:** evita count(*) quando a própria página já revela o total ([cd3a81d](https://github.com/rodrigorrd-dev/ms-pix-manager/commit/cd3a81d8b56882abc90eb9e28c1a9045ca663852))
* **persistence:** evita count(*) quando a própria página já revela o total ([51520ec](https://github.com/rodrigorrd-dev/ms-pix-manager/commit/51520ecd6555282bf172f236b375f516a0830d73))
* **persistence:** insere filhos em lote com batchUpdate ([b6e20be](https://github.com/rodrigorrd-dev/ms-pix-manager/commit/b6e20beb573c1ed6bc5920c2b61c99e1fc4991d7))
* **persistence:** insere filhos em lote com batchUpdate ([fd02db4](https://github.com/rodrigorrd-dev/ms-pix-manager/commit/fd02db467662aadb339af74d3df86688c138e4b1))
* **persistence:** usa UPDATE ... RETURNING em vez de atualizar e reler ([9b89a12](https://github.com/rodrigorrd-dev/ms-pix-manager/commit/9b89a125211808efeef7754106c09c514071c886))
* **persistence:** usa UPDATE ... RETURNING em vez de atualizar e reler ([68f6caa](https://github.com/rodrigorrd-dev/ms-pix-manager/commit/68f6caad36eeef00757cf3006c28107b7f9201ca))
* **schedule,automatic:** cancela cobranças e ocorrências em lote ([2cf6977](https://github.com/rodrigorrd-dev/ms-pix-manager/commit/2cf697788797b2bfe28f28c7d3721bf3749fac5a))

## [0.2.1](https://github.com/rodrigorrd-dev/ms-pix-manager/compare/v0.2.0...v0.2.1) (2026-10-22)


### Correções

* **docker:** atualiza pacotes do Alpine para corrigir ([6c7f515](https://github.com/rodrigorrd-dev/ms-pix-manager/commit/6c7f51501c79414f28fa7deef3a9046c1149877e))
* **docker:** atualiza pacotes do Alpine para corrigir CVE-2026-77214 do libexpat ([4942c04](https://github.com/rodrigorrd-dev/ms-pix-manager/commit/4942c042b1c5d1c03dc61bb176147c09407eebe3))
* **docker:** corrige a CVE do libexpat e torna o AutomaticPixApiIT independente da data ([88bf61a](https://github.com/rodrigorrd-dev/ms-pix-manager/commit/88bf61af0965d11e8aa84fcf994f91426d30fe8f))

## [0.2.0](https://github.com/rodrigorrd-dev/ms-pix-manager/compare/v0.1.0...v0.2.0) (2026-10-17)


### Funcionalidades

* merge feature/contributing-git-flow em develop ([4bc5c6c](https://github.com/rodrigorrd-dev/ms-pix-manager/commit/4bc5c6c5e720a8538f1035bd321d0b98ad3909ff))
* merge feature/issue-taxonomy em develop ([75981e4](https://github.com/rodrigorrd-dev/ms-pix-manager/commit/75981e4ae0624a48ade18515e06353149efcc277))
* merge feature/quality-gates em develop ([dbc6e43](https://github.com/rodrigorrd-dev/ms-pix-manager/commit/dbc6e430fd49521b6bdea33393984cdf1848e957))
* **ops:** métricas e alertas de entregas de webhook DEAD e vencidas ([20fe1f7](https://github.com/rodrigorrd-dev/ms-pix-manager/commit/20fe1f75051fa761475f4921bcb741a6e1d36975))
* **pix:** adiciona pix agendado, pix automático e casos de MED ([7e7ae6a](https://github.com/rodrigorrd-dev/ms-pix-manager/commit/7e7ae6a3e86fbcc645daaf14208872b532136c4f))
* **security:** recusa insecure-tls e allow-unverified-chain em hml e prd ([a165fcb](https://github.com/rodrigorrd-dev/ms-pix-manager/commit/a165fcb9275a6022e4933f404b869696df76d984))
* **webhook:** exige https, valida TLS e restringe o reenvio ao escopo webhook.admin ([4a4307d](https://github.com/rodrigorrd-dev/ms-pix-manager/commit/4a4307dc4c9a394a6474774fa2e6ec2c69cf1cb0))


### Correções

* **security:** remove o TrustManager que aceita todos os certificados ([e4585a1](https://github.com/rodrigorrd-dev/ms-pix-manager/commit/e4585a182a8bb834f030fde0867321c4c32c7138))

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
