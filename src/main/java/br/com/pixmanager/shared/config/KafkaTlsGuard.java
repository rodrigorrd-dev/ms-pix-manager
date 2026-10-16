package br.com.pixmanager.shared.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * Fail-fast do TLS do Kafka. O Spring NÃO falha quando falta a variável de um {@code ${...}} sem default dentro de um profile:
 * a propriedade fica com o texto literal e o erro só estoura (obscuro) quando o primeiro consumer é criado. Aqui o app recusa subir,
 * dizendo qual variável falta. Só vale com os profiles {@code kafka-tls} / {@code kafka-mtls} (veja o fim do application.yml).
 */
@Configuration
@Profile({"kafka-tls", "kafka-mtls"})
public class KafkaTlsGuard {

    private final Environment env;

    public KafkaTlsGuard(Environment env) {
        this.env = env;
    }

    @PostConstruct
    void validate() {
        if (env.acceptsProfiles(org.springframework.core.env.Profiles.of("kafka-tls"))) {
            require("spring.kafka.ssl.trust-store-location", "KAFKA_SSL_TRUSTSTORE_LOCATION");
            require("spring.kafka.ssl.trust-store-password", "KAFKA_SSL_TRUSTSTORE_PASSWORD");
        }
        if (env.acceptsProfiles(org.springframework.core.env.Profiles.of("kafka-mtls"))) {
            require("spring.kafka.ssl.key-store-location", "KAFKA_SSL_KEYSTORE_LOCATION");
            require("spring.kafka.ssl.key-store-password", "KAFKA_SSL_KEYSTORE_PASSWORD");
            require("spring.kafka.ssl.key-password", "KAFKA_SSL_KEY_PASSWORD");
        }
    }

    private void require(String property, String variable) {
        String value;
        try {
            value = env.getRequiredProperty(property);          // resolve ${...} de forma estrita: lança se a variável não existe
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new IllegalStateException("Profile de TLS do Kafka ativo, mas a variável " + variable + " não foi definida", e);
        }
        if (value.isBlank()) {
            throw new IllegalStateException("Profile de TLS do Kafka ativo, mas a variável " + variable + " está em branco");
        }
    }
}
