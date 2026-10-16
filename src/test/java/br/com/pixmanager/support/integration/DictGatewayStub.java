package br.com.pixmanager.support.integration;

import com.github.tomakehurst.wiremock.WireMockServer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/** {@code dict-gateway} simulado (contrato rascunho — docs/05 §6b). Servidor ÚNICO por JVM; cada teste reconfigura os stubs. */
public final class DictGatewayStub {

    public static final WireMockServer SERVER = new WireMockServer(options().dynamicPort());

    static {
        SERVER.start();
    }

    private DictGatewayStub() {}

    public static String baseUrl() {
        return "http://localhost:" + SERVER.port();
    }

    public static void reset() {
        SERVER.resetAll();
    }

    public static void registerOk(String cid) {
        SERVER.stubFor(post(urlEqualTo("/dict/v1/entries")).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json").withBody("{\"cid\":\"" + cid + "\",\"registeredAt\":\"2026-10-15T12:00:00Z\"}")));
    }

    public static void registerStatus(int status) {
        SERVER.stubFor(post(urlEqualTo("/dict/v1/entries")).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody("{\"message\":\"recusado\"}")));
    }

    public static void deleteOk() {
        SERVER.stubFor(post(urlEqualTo("/dict/v1/entries/deletion")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{}")));
    }

    public static void deleteStatus(int status) {
        SERVER.stubFor(post(urlEqualTo("/dict/v1/entries/deletion")).willReturn(aResponse().withStatus(status)));
    }

    public static void lookupFound(String ownerDocument) {
        SERVER.stubFor(post(urlEqualTo("/dict/v1/entries/lookup")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"owner":{"name":"Joao Favorecido","document":"%s"},
                         "account":{"ispb":"87654321","branch":"0002","number":"55555","type":"CACC"},
                         "ownershipDate":"2024-01-10T10:00:00Z"}""".formatted(ownerDocument))));
    }

    public static void lookupNotFound() {
        SERVER.stubFor(post(urlEqualTo("/dict/v1/entries/lookup")).willReturn(aResponse().withStatus(404)
                .withHeader("Content-Type", "application/json").withBody("{}")));
    }
}
