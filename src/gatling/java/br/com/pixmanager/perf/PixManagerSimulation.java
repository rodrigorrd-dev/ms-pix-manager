package br.com.pixmanager.perf;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.header;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * Carga do ms-pix-manager (docs/08 §3.9). Rodar: {@code ./mvn.sh -Pperf gatling:test} (stack local no ar:
 * {@code docker compose up -d}, com o seed {@code docker/seed/dev-seed.sql}). NUNCA em ambiente compartilhado.
 *
 * <p>Cobre todas as famílias de endpoint, em cenários independentes que dividem o RPS alvo:
 * cobrança imediata (criar/consultar/304/listar), cobrança com vencimento, ordens de pagamento (criar, replay idempotente,
 * consultar, listar, decodificar), chaves Pix (listar/lookup), limites e Pix recebidos (listar). Os cenários de listagem são
 * os que exercitam o N+1 já corrigido em ChargePersistenceAdapter/ReceivedPixPersistenceAdapter: rode antes/depois para comparar.
 *
 * <p>Parâmetros ({@code -Dnome=valor}); os defaults são de FUMAÇA, o perfil do docs/08 é {@code -Dpix.rps=300 -Dpix.ramp.seconds=120 -Dpix.hold.seconds=900}:
 * pix.base-url (http://localhost:8088), pix.token-url (Keycloak do compose), pix.client-id/secret (partner e backoffice de DEV),
 * pix.account-id (conta do seed), pix.key (chave EVP do seed), pix.rps (20), pix.ramp.seconds (30), pix.hold.seconds (60),
 * pix.p95.ms (150), pix.fail.percent (0.1).
 */
public class PixManagerSimulation extends Simulation {

    private static final String BASE_URL = prop("pix.base-url", "http://localhost:8088");
    private static final String TOKEN_URL = prop("pix.token-url", "http://localhost:18180/realms/pix/protocol/openid-connect/token");
    private static final String CLIENT_SECRET = prop("pix.client-secret", "dev-secret-local-only");
    private static final String PARTNER_CLIENT = prop("pix.partner-client", "pix-partner-dev");
    private static final String BACKOFFICE_CLIENT = prop("pix.backoffice-client", "pix-backoffice-dev");
    private static final String ACCOUNT_ID = prop("pix.account-id", "00000000-0000-0000-0000-000000000001");
    private static final String PIX_KEY = prop("pix.key", "123e4567-e89b-42d3-a456-426655440000");
    private static final double RPS = Double.parseDouble(prop("pix.rps", "20"));
    private static final int RAMP_SECONDS = Integer.parseInt(prop("pix.ramp.seconds", "30"));
    private static final int HOLD_SECONDS = Integer.parseInt(prop("pix.hold.seconds", "60"));
    private static final double P95_MS = Double.parseDouble(prop("pix.p95.ms", "150"));
    private static final double MAX_FAIL_PERCENT = Double.parseDouble(prop("pix.fail.percent", "0.1"));

    private static final String ALNUM = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final Pattern ACCESS_TOKEN = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");

    // tokens renovados em segundo plano: o access token do Keycloak dura minutos e a carga dura mais que isso
    private static volatile String partnerToken;
    private static volatile String backofficeToken;
    private static ScheduledExecutorService refresher;

    private final HttpProtocolBuilder protocol = http.baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .shareConnections();

    {
        // ---------------------------------------------------------------- cobrança imediata (partner)
        ChainBuilder createCharge = exec(session -> session.set("txid", randomTxid()))
                .exec(partner(http("PUT /cob/{txid}").put("/api/pix/v2/cob/#{txid}"))
                        .body(StringBody(ChargeBodies.COB)).check(status().is(201))
                        .check(header("ETag").saveAs("etag")));

        ScenarioBuilder chargeFlow = scenario("cob: criar + consultar + 304")
                .exec(createCharge)
                .exec(partner(http("GET /cob/{txid}").get("/api/pix/v2/cob/#{txid}")).check(status().is(200)))
                .exec(partner(http("GET /cob/{txid} (If-None-Match)").get("/api/pix/v2/cob/#{txid}")
                        .header("If-None-Match", "#{etag}")).check(status().is(304)));

        ScenarioBuilder chargeSearch = scenario("cob: listar (1+2N antes da correção)")
                .exec(partner(http("GET /cob?inicio&fim").get("/api/pix/v2/cob")
                        .queryParam("inicio", session -> Instant.now().minus(Duration.ofDays(1)).toString())
                        .queryParam("fim", session -> Instant.now().plus(Duration.ofDays(1)).toString())
                        .queryParam("paginacao.itensPorPagina", "100"))
                        .check(status().is(200)));

        ScenarioBuilder chargeSearchByStatus = scenario("cob: listar por status ATIVA")
                .exec(partner(http("GET /cob?status=ATIVA").get("/api/pix/v2/cob")
                        .queryParam("inicio", session -> Instant.now().minus(Duration.ofDays(1)).toString())
                        .queryParam("fim", session -> Instant.now().plus(Duration.ofDays(1)).toString())
                        .queryParam("status", "ATIVA").queryParam("paginacao.itensPorPagina", "50"))
                        .check(status().is(200)));

        // ---------------------------------------------------------------- cobrança com vencimento (partner)
        ScenarioBuilder dueCharge = scenario("cobv: criar + consultar")
                .exec(session -> session.set("txid", randomTxid()))
                .exec(partner(http("PUT /cobv/{txid}").put("/api/pix/v2/cobv/#{txid}"))
                        .body(StringBody(ChargeBodies.COBV)).check(status().is(201)))
                .exec(partner(http("GET /cobv/{txid}").get("/api/pix/v2/cobv/#{txid}")).check(status().is(200)));

        // ---------------------------------------------------------------- Pix recebidos (partner)
        ScenarioBuilder receivedPix = scenario("pix: listar recebidos")
                .exec(partner(http("GET /pix?inicio&fim").get("/api/pix/v2/pix")
                        .queryParam("inicio", session -> Instant.now().minus(Duration.ofDays(30)).toString())
                        .queryParam("fim", session -> Instant.now().plus(Duration.ofDays(1)).toString())
                        .queryParam("paginacao.itensPorPagina", "100"))
                        .check(status().is(200)));

        // ---------------------------------------------------------------- ordens de pagamento (backoffice)
        ScenarioBuilder payment = scenario("payment-orders: criar + replay + consultar + listar")
                .exec(session -> session.set("idemKey", UUID.randomUUID().toString()))
                .exec(backoffice(http("POST /payment-orders").post("/v1/payment-orders")
                        .header("Idempotency-Key", "#{idemKey}"))
                        .body(StringBody(ChargeBodies.payment(ACCOUNT_ID))).check(status().is(201))
                        .check(jsonPath("$.id").saveAs("orderId")))
                .exec(backoffice(http("POST /payment-orders (replay idempotente)").post("/v1/payment-orders")
                        .header("Idempotency-Key", "#{idemKey}"))
                        .body(StringBody(ChargeBodies.payment(ACCOUNT_ID)))
                        .check(status().in(200, 201)).check(header("Idempotent-Replayed").is("true")))
                .exec(backoffice(http("GET /payment-orders/{id}").get("/v1/payment-orders/#{orderId}")).check(status().is(200)))
                .exec(backoffice(http("GET /payment-orders?payerAccountId").get("/v1/payment-orders")
                        .queryParam("payerAccountId", ACCOUNT_ID).queryParam("size", "20")).check(status().is(200)));

        // ---------------------------------------------------------------- chaves, limites (backoffice)
        ScenarioBuilder keys = scenario("pix-keys: listar + lookup")
                .exec(backoffice(http("GET /pix-keys?accountId").get("/v1/pix-keys")
                        .queryParam("accountId", ACCOUNT_ID).queryParam("size", "20")).check(status().is(200)))
                .exec(backoffice(http("POST /pix-keys/lookups").post("/v1/pix-keys/lookups"))
                        .body(StringBody(ChargeBodies.lookup(ACCOUNT_ID))).check(status().is(200)));

        ScenarioBuilder limits = scenario("limits: consultar")
                .exec(backoffice(http("GET /limits/accounts/{id}").get("/v1/limits/accounts/" + ACCOUNT_ID)).check(status().is(200)));

        // Divisão do RPS alvo: escrita de cobrança e pagamento são o caminho quente; as listagens, o N+1.
        setUp(
                open(chargeFlow, 0.30), open(chargeSearch, 0.10), open(chargeSearchByStatus, 0.05), open(dueCharge, 0.10),
                open(receivedPix, 0.05), open(payment, 0.25), open(keys, 0.10), open(limits, 0.05))
                .protocols(protocol)
                .assertions(
                        global().responseTime().percentile(95).lt((int) P95_MS),
                        global().failedRequests().percent().lt(MAX_FAIL_PERCENT));
    }

    // ------------------------------------------------------------------ plumbing

    /** Rampa 0→share·RPS em RAMP_SECONDS e sustenta por HOLD_SECONDS. */
    private static io.gatling.javaapi.core.PopulationBuilder open(ScenarioBuilder scenario, double share) {
        double target = Math.max(RPS * share, 0.1);
        int steps = 5;                                     // degraus de rampa (o DSL só aceita rampa em inteiros; RPS fracionário vale aqui)
        return scenario.injectOpen(
                incrementUsersPerSec(target / steps).times(steps).eachLevelLasting(Math.max(1, RAMP_SECONDS / steps)).startingFrom(0),
                constantUsersPerSec(target).during(Duration.ofSeconds(HOLD_SECONDS)));
    }

    private static io.gatling.javaapi.http.HttpRequestActionBuilder partner(io.gatling.javaapi.http.HttpRequestActionBuilder request) {
        return request.header("Authorization", session -> "Bearer " + partnerToken);
    }

    private static io.gatling.javaapi.http.HttpRequestActionBuilder backoffice(io.gatling.javaapi.http.HttpRequestActionBuilder request) {
        return request.header("Authorization", session -> "Bearer " + backofficeToken);
    }

    private static String randomTxid() {
        StringBuilder builder = new StringBuilder(32);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int index = 0; index < 32; index++) {
            builder.append(ALNUM.charAt(random.nextInt(ALNUM.length())));
        }
        return builder.toString();
    }

    @Override
    public void before() {
        refreshTokens();                                   // falha cedo (e alto) se o Keycloak/seed não estiverem no ar
        refresher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "token-refresher");
            thread.setDaemon(true);
            return thread;
        });
        refresher.scheduleAtFixedRate(() -> {
            try {
                refreshTokens();
            } catch (RuntimeException exception) {
                System.err.println("Falha ao renovar token (mantém o anterior): " + exception.getMessage());
            }
        }, 60, 60, TimeUnit.SECONDS);
        System.out.printf("PixManagerSimulation: %s rps=%.0f ramp=%ds hold=%ds%n", BASE_URL, RPS, RAMP_SECONDS, HOLD_SECONDS);
    }

    @Override
    public void after() {
        if (refresher != null) {
            refresher.shutdownNow();
        }
    }

    private static void refreshTokens() {
        partnerToken = fetchToken(PARTNER_CLIENT);
        backofficeToken = fetchToken(BACKOFFICE_CLIENT);
    }

    private static String fetchToken(String clientId) {
        try {
            String form = "grant_type=client_credentials&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                    + "&client_secret=" + URLEncoder.encode(CLIENT_SECRET, StandardCharsets.UTF_8);
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(TOKEN_URL)).timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                    HttpResponse.BodyHandlers.ofString());
            Matcher matcher = ACCESS_TOKEN.matcher(response.body());
            if (response.statusCode() != 200 || !matcher.find()) {
                throw new IllegalStateException("token de " + clientId + " em " + TOKEN_URL + " -> HTTP " + response.statusCode());
            }
            return matcher.group(1);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("não consegui o token de " + clientId + ": " + e.getMessage(), e);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static String prop(String name, String fallback) {
        return System.getProperty(name, fallback);
    }

    /** Corpos JSON (contrato BCB em português nas cobranças; inglês nas APIs de gestão). */
    private static final class ChargeBodies {
        static final String COB = """
                {"calendario":{"expiracao":3600},"devedor":{"cpf":"52998224725","nome":"Fulano de Tal"},
                 "valor":{"original":"123.45"},"chave":"%s","solicitacaoPagador":"Carga Gatling",
                 "infoAdicionais":[{"nome":"Pedido","valor":"1234"},{"nome":"Canal","valor":"gatling"}]}
                """.formatted(PIX_KEY);
        static final String COBV = """
                {"calendario":{"dataDeVencimento":"2030-12-31","validadeAposVencimento":30},
                 "devedor":{"cnpj":"11222333000181","nome":"Empresa Teste Ltda"},
                 "valor":{"original":"500.00"},"chave":"%s","solicitacaoPagador":"Fatura Gatling"}
                """.formatted(PIX_KEY);

        static String payment(String accountId) {
            // valor mínimo: o limite diário do dispositivo não cadastrado (R$1.000) não pode esgotar durante a carga
            return """
                    {"payerAccountId":"%s","method":"KEY","amount":"0.01","payerMessage":"carga",
                     "destination":{"pixKeyType":"EMAIL","pixKey":"carga@exemplo.com.br"}}
                    """.formatted(accountId);
        }

        static String lookup(String accountId) {
            return """
                    {"payerAccountId":"%s","pixKeyType":"EMAIL","pixKey":"carga@exemplo.com.br"}
                    """.formatted(accountId);
        }
    }
}
