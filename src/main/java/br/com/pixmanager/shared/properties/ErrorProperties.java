package br.com.pixmanager.shared.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Base do {@code type} do Problem Detail (RFC 9457): {@code baseTypeUri + código}. */
@ConfigurationProperties(prefix = "pix.errors")
public record ErrorProperties(String baseTypeUri) {

    public ErrorProperties {
        if (baseTypeUri == null || baseTypeUri.isBlank()) {
            baseTypeUri = "https://errors.pixmanager.example/";
        }
    }
}
