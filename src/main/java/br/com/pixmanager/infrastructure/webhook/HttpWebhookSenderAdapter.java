package br.com.pixmanager.infrastructure.webhook;

import br.com.pixmanager.core.port.out.WebhookSenderPortOut;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.Socket;
import java.security.cert.X509Certificate;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;

/**
 * POST do webhook. Segurança do receptor: cabeçalhos {@code x-pix-timestamp} (epoch seconds) e {@code x-pix-signature: v1=<hex>} onde
 * {@code hex = HMAC-SHA256(chave, timestamp + "." + corpo)} — o receptor recalcula e rejeita timestamp velho (> 5 min: anti-replay). Não segue
 * redirecionamentos (um 30x poderia levar a um host interno), tem timeouts curtos e não lê o corpo da resposta (só o status).
 */
@Component
public class HttpWebhookSenderAdapter implements WebhookSenderPortOut {

    private final HttpClient client;
    private final SecretKeySpec signingKey;
    private final Duration requestTimeout;
    private final Clock clock;

    public HttpWebhookSenderAdapter(@Value("${pix.webhook.signing-key-base64:}") String signingKeyBase64,
                                    @Value("${pix.webhook.connect-timeout:3s}") Duration connectTimeout,
                                    @Value("${pix.webhook.request-timeout:5s}") Duration requestTimeout,
                                    @Value("${pix.webhook.insecure-tls:false}") boolean insecureTls, Clock clock) {
        if (signingKeyBase64 == null || signingKeyBase64.isBlank()) {
            throw new IllegalStateException("pix.webhook.signing-key-base64 (PIX_WEBHOOK_SIGNING_KEY) é obrigatório: assina os webhooks enviados");
        }
        byte[] key = Base64.getDecoder().decode(signingKeyBase64);
        if (key.length < 32) {
            throw new IllegalStateException("pix.webhook.signing-key-base64 deve ter ao menos 32 bytes");
        }
        this.signingKey = new SecretKeySpec(key, "HmacSHA256");
        HttpClient.Builder builder = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER);
        if (insecureTls) {
            LoggerFactory.getLogger(HttpWebhookSenderAdapter.class).warn("pix.webhook.insecure-tls=true: o certificado do receptor NÃO é validado. SÓ dev/teste");
            builder.sslContext(trustAllContext());
        }
        this.client = builder.build();
        this.requestTimeout = requestTimeout;
        this.clock = clock;
    }

    @Override
    public SendResult send(String url, String body) {
        try {
            String timestamp = String.valueOf(clock.instant().getEpochSecond());
            HttpRequest request = HttpRequest.newBuilder(URI.create(url.trim().replaceAll("/+$", "") + "/pix")).timeout(requestTimeout)
                    .header("Content-Type", "application/json").header("User-Agent", "ms-pix-manager")
                    .header("x-pix-timestamp", timestamp).header("x-pix-signature", "v1=" + sign(timestamp, body))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return new SendResult(response.statusCode(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SendResult(null, "interrompido");
        } catch (IOException | RuntimeException e) {
            return new SendResult(null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** SÓ dev/teste (receptores com certificado autoassinado). Em hml/prd o padrão é validar a cadeia normalmente. */
    private static SSLContext trustAllContext() {
        try {
            // X509ExtendedTrustManager (e não X509TrustManager): o JDK embrulha o simples e AINDA verifica o nome do host
            TrustManager trustAll = new X509ExtendedTrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String t) { }
                public void checkServerTrusted(X509Certificate[] c, String t) { }
                public void checkClientTrusted(X509Certificate[] c, String t, Socket s) { }
                public void checkServerTrusted(X509Certificate[] c, String t, Socket s) { }
                public void checkClientTrusted(X509Certificate[] c, String t, SSLEngine e) { }
                public void checkServerTrusted(X509Certificate[] c, String t, SSLEngine e) { }
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{trustAll}, new java.security.SecureRandom());
            return context;
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    String sign(String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(signingKey);
            return HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
