#!/usr/bin/env bash
# Roda o Maven num container (o host não tem mvn). Cache de dependências em ~/.m2; arquivos criados com o SEU uid
# (sem isso o target/ fica root e você não consegue limpá-lo). Uso: ./mvn.sh -q test
# Testcontainers dentro do container usa o socket do Docker do host (mesma rede: --network host).
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p "$HOME/.m2"
exec docker run --rm \
  --user "$(id -u):$(id -g)" \
  -e HOME=/tmp/home -e MAVEN_CONFIG=/tmp/home/.m2 \
  -v "$PWD":/app -w /app \
  -v "$HOME/.m2":/tmp/home/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  --group-add "$(stat -c %g /var/run/docker.sock)" \
  --network host \
  -e TESTCONTAINERS_HOST_OVERRIDE=localhost \
  -e TESTCONTAINERS_RYUK_DISABLED=true \
  maven:3.9-eclipse-temurin-21 mvn -B -Dmaven.repo.local=/tmp/home/.m2/repository "$@"
