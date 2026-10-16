package br.com.pixmanager.support.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.util.Base64;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/** PSP do recebedor simulado (HTTPS): serve o payload JWS de QR dinâmico assinado com o certificado de teste. Servidor ÚNICO por JVM. */
public final class QrReceiverStub {

    public static final WireMockServer SERVER = new WireMockServer(options().dynamicPort().dynamicHttpsPort());
    private static final char[] PW = "changeit".toCharArray();

    static {
        SERVER.start();
    }

    private QrReceiverStub() {}

    /** URL como vai no BR Code: sem esquema. */
    public static String urlFor(String path) {
        return "localhost:" + SERVER.httpsPort() + path;
    }

    public static void publish(String path, String payloadJson) {
        SERVER.stubFor(get(urlEqualTo(path)).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/jose").withBody(sign(payloadJson))));
    }

    public static void status(String path, int status) {
        SERVER.stubFor(get(urlEqualTo(path)).willReturn(aResponse().withStatus(status)));
    }

    public static String sign(String payloadJson) {
        try (InputStream in = QrReceiverStub.class.getResourceAsStream("/qr/leaf.p12")) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(in, PW);
            var chain = new ArrayList<Base64>();
            for (Certificate c : ks.getCertificateChain("leaf")) {
                chain.add(Base64.encode(((X509Certificate) c).getEncoded()));
            }
            JWSObject jws = new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256).x509CertChain(chain).build(), new Payload(payloadJson));
            jws.sign(new RSASSASigner((PrivateKey) ks.getKey("leaf", PW)));
            return jws.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String cob(String txid, String key, String amount, String status) {
        return "{\"txid\":\"%s\",\"status\":\"%s\",\"chave\":\"%s\",\"calendario\":{\"criacao\":\"%s\",\"expiracao\":3600},\"valor\":{\"original\":\"%s\"}}"
                .formatted(txid, status, key, java.time.Instant.now().toString(), amount);
    }
}
