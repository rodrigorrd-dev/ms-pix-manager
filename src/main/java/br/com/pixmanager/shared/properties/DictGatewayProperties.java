package br.com.pixmanager.shared.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Cliente do {@code dict-gateway}: timeouts connect/read separados. Cache, limite de consultas e retry ficam no gateway. */
@ConfigurationProperties(prefix = "pix.dict-gateway")
public record DictGatewayProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {

    public DictGatewayProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://localhost:18091" : baseUrl;
        connectTimeout = connectTimeout != null ? connectTimeout : Duration.ofMillis(500);
        readTimeout = readTimeout != null ? readTimeout : Duration.ofSeconds(3);
    }
}
