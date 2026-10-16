package br.com.pixmanager.infrastructure.webhook;

import br.com.pixmanager.core.port.out.WebhookSenderPortOut;
import org.springframework.beans.factory.annotation.Value;
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
                                    @Value("${pix.webhook.request-timeout:5s}") Duration requestTimeout, Clock clock) {
        if (signingKeyBase64 == null || signingKeyBase64.isBlank()) {
            throw new IllegalStateException("pix.webhook.signing-key-base64 (PIX_WEBHOOK_SIGNING_KEY) é obrigatório: assina os webhooks enviados");
        }
        byte[] key = Base64.getDecoder().decode(signingKeyBase64);
        if (key.length < 32) {
            throw new IllegalStateException("pix.webhook.signing-key-base64 deve ter ao menos 32 bytes");
        }
        this.signingKey = new SecretKeySpec(key, "HmacSHA256");
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
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
