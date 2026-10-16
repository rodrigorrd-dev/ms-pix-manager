#!/bin/sh
# Roda DENTRO do compose (serviço connector-init): registra o conector de outbox. Idempotente (PUT /config cria ou atualiza).
# Usa só sh + curl + sed (imagem curlimages/curl). Credenciais vêm das variáveis do serviço, não do JSON.
set -eu
NAME="pix-outbox-connector"
# Extrai o bloco "config" (tudo entre '"config": {' e a chave de fechamento externa) e troca as credenciais do banco.
CONFIG="$(sed -n '/"config": {/,$p' /debezium/outbox-connector.json | sed '1s/.*/{/' | sed '$d' \
  | sed -e "s|\"database.user\": \"[^\"]*\"|\"database.user\": \"${POSTGRES_USER}\"|" \
        -e "s|\"database.password\": \"[^\"]*\"|\"database.password\": \"${POSTGRES_PASSWORD}\"|" \
        -e "s|\"database.dbname\": \"[^\"]*\"|\"database.dbname\": \"${POSTGRES_DB}\"|")"
curl -fsS -X PUT -H 'Content-Type: application/json' --data "$CONFIG" "$CONNECT_URL/connectors/$NAME/config"
echo
curl -fsS "$CONNECT_URL/connectors/$NAME/status"
echo
