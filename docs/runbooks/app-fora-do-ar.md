# App fora do ar / degradação (`PixAppDown`, `PixHighLatencyP95`, `PixErrorRate`, `PixDbPoolSaturated`)

**Sintoma:** o Prometheus não consegue raspar o app (`up == 0`) por 2 min; ou p95 > 150 ms, 5xx > 1%, threads esperando conexão do pool.
**Impacto:** sem API (cobranças, chaves, pagamentos). Eventos já gravados na outbox continuam sendo publicados pelo Debezium (independe do app).

## Diagnóstico
1. O processo vive? `docker ps` / `kubectl get pods`; probes `GET :8081/actuator/health/liveness` e `/readiness` (porta de gerenciamento em hml/prd; `8080` no dev).
2. Logs do início: `docker logs pix-app | grep -E "APPLICATION FAILED|IllegalStateException|Caused by" | head`. Causas conhecidas de **não subir** (fail-fast): `PIX_ISPB` inválido, chaves `PIX_CRYPTO_*` ausentes/iguais, `OAUTH2_ISSUER_URI`/`OAUTH2_AUDIENCE` ausentes em hml/prd, profile `kafka-tls` sem `KAFKA_SSL_TRUSTSTORE_*`, banco inacessível, Liquibase falhando.
3. Banco: `select count(*) from pg_stat_activity where datname='pixmanager';` contra `max_connections`; pool Hikari no dashboard "Pool de conexões".
4. Latência: dashboard "Latência p95 por rota"; comparar com chamadas externas (DICT/core) — um circuit breaker aberto ou timeouts altos degradam as rotas que dependem deles.

## Ação
- Falha de configuração: corrija a variável e reinicie; o log diz qual.
- Pool saturado: confirme se há consulta lenta (`pg_stat_activity` ordenado por `now()-query_start`) antes de aumentar `DB_POOL_MAX` (soma de todas as réplicas ≤ `max_connections`).
- OOM: o contêiner sai (`-XX:+ExitOnOutOfMemoryError`) e deve ser reiniciado pela plataforma; aumente memória e investigue o heap (`jvm_memory_used_bytes`).
- Reinício seguro: `server.shutdown=graceful` (≥ 30 s de `terminationGracePeriod`). Jobs são idempotentes (ShedLock).

## Escalonamento
Não volta em 10 min, ou erro de dados/migração: time de plataforma + dono do serviço.
