#!/usr/bin/env bash
# Registra/atualiza o conector de outbox de forma idempotente (PUT /config).
# Configuração vem do .env da raiz (veja .env.example); variável já exportada no shell tem prioridade.
#   CONNECT_URL, POSTGRES_USER, POSTGRES_PASSWORD, POSTGRES_DB, CONNECTOR_DB_HOST, CONNECTOR_DB_PORT
# Outro arquivo:  ENV_FILE=/caminho/.env ./register-connector.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="${ENV_FILE:-$HERE/../../.env}"

if [[ -f "$ENV_FILE" ]]; then
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" =~ ^[[:space:]]*# || -z "${line//[[:space:]]/}" || "$line" != *=* ]] && continue
    key="${line%%=*}"; value="${line#*=}"
    [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
    [[ -z "${!key+x}" ]] && export "$key=$value"        # não sobrescreve o que já veio do ambiente
  done < "$ENV_FILE"
fi

export CONNECT_URL="${CONNECT_URL:-http://localhost:${CONNECT_PORT:-18083}}"
NAME="pix-outbox-connector"

# Extrai somente o bloco "config" do JSON, aplica as credenciais/host do ambiente e envia por PUT (cria ou atualiza).
CONFIG="$(python3 -c '
import json, os, sys
config = json.load(open(sys.argv[1]))["config"]
overrides = {
    "database.hostname": os.environ.get("CONNECTOR_DB_HOST"),
    "database.port": os.environ.get("CONNECTOR_DB_PORT"),
    "database.user": os.environ.get("POSTGRES_USER"),
    "database.password": os.environ.get("POSTGRES_PASSWORD"),
    "database.dbname": os.environ.get("POSTGRES_DB"),
}
config.update({k: v for k, v in overrides.items() if v})
print(json.dumps(config))
' "$HERE/outbox-connector.json")"
curl -fsS -X PUT -H 'Content-Type: application/json' --data "$CONFIG" "$CONNECT_URL/connectors/$NAME/config"
echo
curl -fsS "$CONNECT_URL/connectors/$NAME/status"
echo
