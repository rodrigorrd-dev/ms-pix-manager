#!/usr/bin/env python3
"""Teste de caos no ambiente local (docker compose): derruba dependências e mede se o sistema se recupera SOZINHO.

  python3 scripts/chaos-test.py [core|kafka|debezium|postgres|all]      (padrão: all)

Cenários (cada um verifica a falha E a recuperação, e imprime o tempo):
  core      core-ledger (mock) fora: pagamento nasce VALIDATED, não reserva; ao voltar, vira RESERVED sozinho (sem reiniciar o app)
  kafka     Kafka fora: a API segue gravando; a outbox acumula; ao voltar o evento chega ao tópico
  debezium  Kafka Connect fora: o slot de replicação acumula (métrica); ao voltar drena e os eventos saem
  postgres  Postgres fora: a API responde erro (não trava); ao voltar, volta a responder sem reiniciar o app

SÓ DEV: para/sobe contêineres do compose. Não rode em ambiente compartilhado. Requer o stack no ar e o seed. Só stdlib.
"""
import json, os, subprocess, sys, time, urllib.parse, urllib.request, uuid

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
B = os.environ.get("PIX_BASE_URL", "http://localhost:8088")
KC = os.environ.get("KEYCLOAK_URL", "http://localhost:18180") + "/realms/pix/protocol/openid-connect/token"
CONNECT = os.environ.get("CONNECT_URL", "http://localhost:18083")
ACC = "00000000-0000-0000-0000-000000000001"
results = []


def token(client):
    d = urllib.parse.urlencode({"grant_type": "client_credentials", "client_id": client, "client_secret": "dev-secret-local-only"}).encode()
    return json.load(urllib.request.urlopen(urllib.request.Request(KC, d), timeout=10))["access_token"]


def api(method, path, body=None, headers=None, tk=None, timeout=10):
    h = {"Authorization": "Bearer " + (tk or O), "Content-Type": "application/json", **(headers or {})}
    r = urllib.request.Request(B + path, data=json.dumps(body).encode() if body is not None else None, method=method, headers=h)
    try:
        x = urllib.request.urlopen(r, timeout=timeout)
        return x.status, json.loads(x.read() or b"null")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, None
    except Exception as e:                                  # conexão recusada / timeout
        return 0, str(e)


def sh(*cmd, check=True):
    return subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, check=check).stdout.strip()


def compose(*args):
    return sh("docker", "compose", *args)


def psql(sql):
    return sh("docker", "exec", "pix-postgres", "psql", "-U", "pix", "-d", "pixmanager", "-Atc", sql)


def wait(cond, timeout=90, every=2):
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            v = cond()
            if v:
                return round(time.time() - t0, 1)
        except Exception:
            pass
        time.sleep(every)
    return None


def check(name, ok, info=""):
    results.append(bool(ok))
    print(("  OK    " if ok else "  FALHA ") + name + (" — " + str(info) if info else ""))


def healthy():
    try:
        return urllib.request.urlopen("http://localhost:8088/actuator/health", timeout=3).status == 200
    except Exception:
        return False


def payment_manual(amount="10.00"):
    body = {"payerAccountId": ACC, "method": "MANUAL", "amount": amount,
            "destination": {"ispb": "60746948", "branch": "0001", "account": "123456", "accountType": "CACC", "name": "Caos", "document": "52998224725"}}
    return api("POST", "/v1/payment-orders", body, {"Idempotency-Key": str(uuid.uuid4())})


def etag_of(path):
    r = urllib.request.Request(B + path, headers={"Authorization": "Bearer " + O})
    return urllib.request.urlopen(r, timeout=10).headers.get("ETag")


def free_keys():
    """Limite de 5 chaves vivas por conta: apaga (pela API) o que o teste criou; nunca a chave do seed."""
    s, l = api("GET", "/v1/pix-keys?accountId=%s&size=100" % ACC)
    for k in (l or {}).get("content", []) if isinstance(l, dict) else []:
        if k["status"] not in ("DELETED", "REJECTED") and k["id"] != "22222222-2222-4222-8222-222222222222":
            try:
                api("POST", "/v1/pix-keys/%s/deletion" % k["id"], {"reason": "USER_REQUESTED"}, {"If-Match": etag_of("/v1/pix-keys/" + k["id"])})
            except Exception:
                pass


def topic_has(topic, needle, timeout_ms=6000):
    """O consumer imprime bytes Avro: compara em BYTES (decodificar como texto estoura e o wait() engolia o erro)."""
    out = subprocess.run(["docker", "exec", "pix-kafka", "kafka-console-consumer", "--bootstrap-server", "localhost:19092", "--topic", topic,
                          "--from-beginning", "--timeout-ms", str(timeout_ms), "--property", "print.key=true"],
                         capture_output=True).stdout
    return needle.encode() in out


def connector_state():
    try:
        return json.load(urllib.request.urlopen(CONNECT + "/connectors/pix-outbox-connector/status", timeout=5))["tasks"][0]["state"]
    except Exception:
        return None


def ensure_connector_running():
    if connector_state() != "RUNNING":
        try:
            urllib.request.urlopen(urllib.request.Request(CONNECT + "/connectors/pix-outbox-connector/restart?includeTasks=true&onlyFailed=false", method="POST"), timeout=10)
        except Exception:
            pass
        wait(lambda: connector_state() == "RUNNING", 60)


def new_key_event():
    """Cria uma chave (gera evento PixKeyRegistered na outbox) e devolve (id)."""
    s, j = api("POST", "/v1/pix-keys", {"accountId": ACC, "pixKeyType": "EVP"}, {"Idempotency-Key": str(uuid.uuid4())})
    return s, (j or {}).get("id") if isinstance(j, dict) else None


def scenario_core():
    print("\n[core] core-ledger indisponível")
    compose("stop", "mocks")
    t0 = time.time()
    s, p = payment_manual("11.00")
    check("API aceita o pagamento mesmo com o core fora (reserva é assíncrona)", s == 201 and p["status"] == "VALIDATED", (s, p.get("status") if isinstance(p, dict) else p))
    time.sleep(12)
    s, cur = api("GET", "/v1/payment-orders/" + p["id"])
    check("sem core, a ordem NÃO é reservada (nada de dinheiro sem reserva)", cur["status"] == "VALIDATED", cur["status"])
    compose("start", "mocks")
    t = wait(lambda: api("GET", "/v1/payment-orders/" + p["id"])[1]["status"] == "RESERVED", 120)
    check("core volta ⇒ a ordem vira RESERVED sozinha, sem reiniciar o app", t is not None, "em %ss após o core voltar" % t)
    check("app seguiu saudável durante todo o incidente", healthy())


def scenario_kafka():
    print("\n[kafka] Kafka indisponível")
    ensure_connector_running()
    compose("stop", "kafka")
    s, kid = new_key_event()
    check("API segue gravando com o Kafka fora (outbox no banco)", s == 201 and kid, s)
    check("app saudável sem Kafka", healthy())
    time.sleep(8)
    compose("start", "kafka")
    ok = wait(lambda: sh("docker", "inspect", "-f", "{{.State.Health.Status}}", "pix-kafka") == "healthy", 120)
    check("Kafka volta saudável", ok is not None, "em %ss" % ok)
    ensure_connector_running()
    t = wait(lambda: topic_has("pix.key.v1", kid), 120, every=5)
    check("o evento gravado durante a queda chega ao tópico depois", t is not None, "em %ss" % t)
    free_keys()


def scenario_debezium():
    print("\n[debezium] Kafka Connect indisponível")
    ensure_connector_running()
    compose("stop", "kafka-connect")
    ids = []
    for _ in range(5):
        s, kid = new_key_event()
        if kid:
            ids.append(kid)
    time.sleep(35)                                              # a métrica do app atualiza a cada 30 s
    lag = int(psql("select coalesce(pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn),0)::bigint from pg_replication_slots where slot_name='pix_outbox_slot'") or 0)
    active = psql("select active from pg_replication_slots where slot_name='pix_outbox_slot'")
    check("com o Connect fora o slot fica inativo e acumula WAL (é isto que o alerta PixOutboxSlotLag mede)", active == "f" and lag > 0, "ativo=%s, lag=%d bytes" % (active, lag))
    compose("start", "kafka-connect")
    t = wait(lambda: connector_state() == "RUNNING", 120)
    check("Connect volta e o conector fica RUNNING", t is not None, "em %ss" % t)
    t2 = wait(lambda: all(topic_has("pix.key.v1", k, 4000) for k in ids[-1:]), 90, every=5)
    check("os eventos acumulados são publicados depois", t2 is not None, "em %ss" % t2)
    t3 = wait(lambda: int(psql("select coalesce(pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn),0)::bigint from pg_replication_slots where slot_name='pix_outbox_slot'") or 0) < 5_000_000, 60)
    check("o slot drena (lag volta ao normal)", t3 is not None)
    free_keys()


def scenario_postgres():
    print("\n[postgres] banco indisponível")
    compose("stop", "postgres")
    time.sleep(3)
    t0 = time.time()
    s, _ = api("GET", "/v1/pix-keys?accountId=%s" % ACC, timeout=15)
    check("sem banco a API responde 503 (indisponível, tente de novo), rápido e sem travar", s == 503 and time.time() - t0 < 12, "status=%s em %.1fs" % (s, time.time() - t0))
    s2, _ = api("POST", "/v1/payment-orders", {"payerAccountId": ACC, "method": "MANUAL", "amount": "1.00"}, {"Idempotency-Key": str(uuid.uuid4())}, timeout=15)
    check("escrita também falha de forma controlada (503, nunca 2xx)", s2 == 503, s2)
    compose("start", "postgres")
    ok = wait(lambda: sh("docker", "inspect", "-f", "{{.State.Health.Status}}", "pix-postgres") == "healthy", 90)
    check("Postgres volta saudável", ok is not None, "em %ss" % ok)
    t = wait(lambda: api("GET", "/v1/pix-keys?accountId=%s" % ACC)[0] == 200, 90)
    check("a API volta a responder 200 SEM reiniciar o app (pool reconecta)", t is not None, "em %ss após o banco voltar" % t)
    ensure_connector_running()
    check("o conector Debezium se recupera após o reinício do banco", wait(lambda: connector_state() == "RUNNING", 90) is not None)


ALL = {"core": scenario_core, "kafka": scenario_kafka, "debezium": scenario_debezium, "postgres": scenario_postgres}

if __name__ == "__main__":
    which = sys.argv[1] if len(sys.argv) > 1 else "all"
    O = token("pix-backoffice-dev")
    free_keys()
    for name, fn in ALL.items():
        if which in ("all", name):
            try:
                fn()
            except Exception as e:                               # nunca deixe o ambiente quebrado por uma exceção do teste
                check("cenário %s terminou sem exceção" % name, False, repr(e))
                compose("start", "postgres", "kafka", "kafka-connect", "mocks")
                O = token("pix-backoffice-dev")
    print("\nRESULTADO: %d/%d verificações OK" % (sum(results), len(results)))
    sys.exit(0 if all(results) else 1)
