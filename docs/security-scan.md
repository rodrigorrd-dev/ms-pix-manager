# Varreduras de segurança — resultados e como repetir

Data: 2026-10-16. Ferramentas executadas de verdade neste repositório (não só configuradas no CI).

## Resumo
| Ferramenta | Alvo | Resultado |
|---|---|---|
| **Trivy 0.57** `fs` (vuln + segredos + misconfig) | `pom.xml`, `Dockerfile`, `docker-compose.yml`, fontes | 3 HIGH em dependências diretas ⇒ **corrigidos** (postgresql 42.7.10→42.7.12, spring-kafka 3.3.14→3.3.16). 0 segredos, 0 misconfig HIGH/CRITICAL |
| **Trivy** `image` (árvore completa de dependências do jar + base Alpine/JRE) | `ms-pix-manager:local` | **30 HIGH/CRITICAL com correção ⇒ 28 corrigidos**; restam **2 CRITICAL** (abaixo) |
| **Semgrep 1.97** (`p/java`, `p/owasp-top-ten`) | `src/main` (323 arquivos) | **0 achados**. ⚠️ 11 arquivos analisados só em parte: o parser Java do Semgrep não entende o `switch` com padrões do Java 21 |
| CodeQL | — | configurado no CI (`.github/workflows/ci.yml`), **ainda não executado** (precisa do GitHub) |
| OWASP dependency-check | — | configurado no job noturno (precisa de `NVD_API_KEY`), **não executado** |
| Teste de intrusão (pentest) | — | **não realizado**. Exige equipe/ferramenta dedicada e ambiente de hml |

## O que foi corrigido
Spring Boot **3.5.13 → 3.5.16** (traz Spring Framework 6.2.19, Micrometer 1.15.12, spring-data 2025.0.13, spring-kafka 3.3.16) e quatro sobrescritas no `pom.xml` com o motivo anotado ao lado de cada uma: `tomcat.version=10.1.61` (6 CRITICAL e 5 HIGH do Tomcat 10.1.53; o 10.1.58 citado como correção não existe no Maven Central), `jackson-bom.version=2.21.7`, `postgresql.version=42.7.12`, `lz4-java=1.11.4`. A suíte inteira foi reexecutada depois: **306 testes unitários + 44 ITs, 0 falhas**.

## Risco residual (aceito conscientemente, a reavaliar)
| CVE | Componente | Correção | Por que não é alcançável aqui |
|---|---|---|---|
| CVE-2026-47884 (CRITICAL, RCE/SSRF) | spring-webmvc 6.2.19 | só no Spring Framework **7.0.9** (Spring Boot 4) | exige `XsltView` com mapeamento `/**` renderizando view; o app é API REST/JSON e não usa views (verificado: nenhum `XsltView`, `ViewResolver`, `ModelAndView`, template engine) |
| CVE-2026-47890 (CRITICAL, corrupção de stream) | spring-webmvc 6.2.19 | idem | exige SSE com *view fragments*; o app não usa SSE nem WebFlux (verificado por busca no código) |

**Condições para manter esta aceitação:** não introduzir `XsltView`, template engines nem SSE/WebFlux sem reavaliar; **planejar a migração para Spring Boot 4** (pendência E1 em `docs/10`: 3.5.x é a última linha 3.x). Rode `trivy image` a cada release; as sobrescritas do `pom.xml` devem ser removidas quando o BOM do Boot alcançá-las.

## Como repetir
```bash
# dependências e configuração
docker run --rm -v "$PWD:/src:ro" -v trivy-cache:/root/.cache aquasec/trivy:0.57.1 fs --scanners vuln,secret,misconfig --severity HIGH,CRITICAL --skip-dirs target,docs /src
# imagem (a que sobe em produção)
docker compose build app
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v trivy-cache:/root/.cache aquasec/trivy:0.57.1 image --severity HIGH,CRITICAL --ignore-unfixed ms-pix-manager:local
# SAST
docker run --rm -v "$PWD:/src" -w /src semgrep/semgrep:1.97.0 semgrep scan --config p/java --config p/owasp-top-ten --metrics=off src/main
```

## Fora do escopo das varreduras (precisa de outra atividade)
Revisão de ameaças (threat model) do fluxo de pagamento, pentest de API (BOLA/IDOR entre contas, abuso de idempotência, enumeração), fuzzing dos consumers Kafka e revisão de configuração do IdP/gateway/ingress.
