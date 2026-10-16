package br.com.pixmanager.shared.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Cliente do core bancário: timeouts de connect e read SEPARADOS (resilience-patterns), sempre explícitos. */
@ConfigurationProperties(prefix = "pix.ledger")
public record CoreLedgerProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {

    public CoreLedgerProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://localhost:18090" : baseUrl;
        connectTimeout = connectTimeout != null ? connectTimeout : Duration.ofMillis(500);
        readTimeout = readTimeout != null ? readTimeout : Duration.ofSeconds(2);
    }
}
