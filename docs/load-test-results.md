# Testes de carga — resultados e como repetir

Ferramenta: **Gatling 3.11** (`src/gatling/java/br/com/pixmanager/perf/PixManagerSimulation.java`, perfil Maven `perf`). Cobre todas as famílias de endpoint: cobrança imediata (criar, consultar, `If-None-Match`/304, listar), cobv, ordens de pagamento (criar, **replay idempotente**, consultar, listar), chaves Pix (listar, lookup), limites e Pix recebidos. Asserções globais: **p95 < 150 ms** e **falhas < 0,1%**.

## Como rodar
```bash
docker compose up -d --build                         # stack local no ar, com seed
./mvn.sh -Pperf test-compile gatling:test            # fumaça: 20 iterações/s, 30 s de subida + 60 s de platô
./mvn.sh -Pperf test-compile gatling:test -Dpix.rps=400 -Dpix.ramp.seconds=30 -Dpix.hold.seconds=90 -Djacoco.skip=true   # carga
# parâmetros: pix.base-url, pix.rps, pix.ramp.seconds, pix.hold.seconds, pix.p95.ms, pix.fail.percent, pix.account-id, pix.key
```
O relatório HTML sai em `target/gatling/<execução>/index.html`. **Nunca rode em ambiente compartilhado** (cria cobranças, ordens e chaves). Uma "iteração" do cenário dispara várias requisições (≈ 2,3 req por iteração), então `pix.rps=400` ≈ 900 req/s.

## Ambiente das medições (leia antes de usar os números)
Máquina de desenvolvimento única, 16 CPUs / 15 GB, **dividida** entre o gerador de carga, o app (1 contêiner), Postgres, Kafka, Schema Registry, Debezium, Keycloak, o stack de observabilidade e outros projetos Docker. Dependências externas são **mocks em Python** (`docker/mocks`), de processo único. Os números abaixo servem para achar **gargalos relativos e regressões**, **não** para dimensionar produção.

## Resultados (2026-10-16)
| Cenário | Requisições | Taxa média | Falhas | p50 | p95 | p99 | máx |
|---|---|---|---|---|---|---|---|
| Fumaça, 20 it/s | 3.672 | 44 req/s | 0 | 3 ms | 20 ms | 79 ms | 537 ms |
| 100 it/s, pool 10 | 24.990 | 236 req/s | 0 | 3 ms | 9 ms | 33 ms | 329 ms |
| 400 it/s, **pool 10** | 104.021 | 905 req/s | 63 (0,06%) | 4 ms | **284 ms** ✗ | 804 ms | 2.257 ms |
| 400 it/s, **pool 30** | 104.032 | 905 req/s | 23 (0,02%) | 5 ms | **65 ms** ✓ | 247 ms | 1.320 ms |

## O que a carga encontrou
1. **Pool de conexões do banco saturado.** Com `DB_POOL_MAX=10` (padrão), a ~900 req/s: pico de **187 requisições esperando conexão**, espera máxima de 1,7 s, CPU do processo em apenas 11% e GC irrelevante (pausa máx. 9 ms). Até rotas baratas (p50 de 1 ms) ficaram com p95 ≈ 260 ms: o gargalo era a fila do pool, não o código. **Correção:** `DB_POOL_MAX=30` (dev/compose) ⇒ p95 284 → 65 ms e p99 804 → 247 ms na mesma carga. Em produção dimensione assim: `pool × réplicas ≤ max_connections do Postgres − reserva` (jobs, Debezium, ferramentas), e **meça** (`hikaricp_connections_pending` deve ficar em 0).
2. **Falhas remanescentes = mock do DICT.** Todas as 23 falhas são `502 dict-gateway indisponível (timeout/IO)`: o mock Python de processo único não acompanha ~900 req/s. O app respondeu como projetado (erro controlado 502, sem 500, sem travar, sem vazar thread) — mas isso também significa que **o limite do app sozinho não foi medido** (o gargalo seguinte foi o mock).
3. **Consistência sob carga (verificada em SQL após todas as execuções):** 23.763 ordens `RESERVED` ↔ 23.763 reservas `RESERVED` no ledger, **0 divergências** (`payment_order` × `ledger_reservation` por `end_to_end_id`). Idempotência: os cenários de replay devolveram a resposta original com `Idempotent-Replayed: true` (as únicas falhas desse cenário vêm do 502 da criação original).

## O que NÃO foi provado
- Capacidade real de produção (hardware dedicado, rede, DICT/core reais com latência real, Postgres com dados de meses).
- Carga **sustentada por horas** (soak): a carga mais longa foi de ~2 min. Faltam teste de soak, de pico súbito (spike) e de crescimento de dados.
- Tempo de recuperação da latência após o pico (não medido).
- Comportamento com **múltiplas réplicas** do app (ShedLock, partições dos consumers).
- O perfil do `docs/08` §3.9 (300 it/s, 120 s de subida, 900 s de platô) **não foi executado inteiro** (rodamos 400 it/s por 90 s).

## Próximos passos
Rodar o perfil completo de `docs/08` em ambiente dedicado, com mocks mais capazes (ou o DICT/core de hml) e 2+ réplicas; acrescentar um teste de soak de 1–2 h e acompanhar vazamento de memória/conexões e crescimento da `outbox_event`.
