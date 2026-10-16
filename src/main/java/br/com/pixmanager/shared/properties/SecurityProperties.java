package br.com.pixmanager.shared.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Resource Server (decisão C2): este ms apenas VALIDA JWT (assinatura via JWKS, {@code iss}, {@code aud}, {@code exp}).
 * Não emite token nem cadastra cliente. Sem {@code jwkSetUri} não há {@code JwtDecoder} (testes injetam o seu).
 */
@ConfigurationProperties(prefix = "pix.security.jwt")
public record SecurityProperties(String jwkSetUri, String issuer, String audience) {}
