package br.com.pixmanager.shared.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KafkaTlsGuardTest {

    @Test
    @DisplayName("kafka-tls sem a variável do truststore (placeholder não resolvido) recusa subir e nomeia a variável")
    void tlsSemVariavel() {
        MockEnvironment env = new MockEnvironment().withProperty("spring.kafka.ssl.trust-store-location", "${KAFKA_SSL_TRUSTSTORE_LOCATION}");
        env.setActiveProfiles("kafka-tls");
        assertThatThrownBy(() -> new KafkaTlsGuard(env).validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("KAFKA_SSL_TRUSTSTORE_LOCATION");
    }

    @Test
    @DisplayName("kafka-tls com valor em branco recusa subir")
    void tlsEmBranco() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.kafka.ssl.trust-store-location", "file:/certs/t.jks").withProperty("spring.kafka.ssl.trust-store-password", " ");
        env.setActiveProfiles("kafka-tls");
        assertThatThrownBy(() -> new KafkaTlsGuard(env).validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("KAFKA_SSL_TRUSTSTORE_PASSWORD");
    }

    @Test
    @DisplayName("kafka-tls + kafka-mtls completos sobem; mtls exige keystore e senha da chave")
    void completoSobe() {
        MockEnvironment ok = new MockEnvironment()
                .withProperty("spring.kafka.ssl.trust-store-location", "file:/c/t.jks").withProperty("spring.kafka.ssl.trust-store-password", "p")
                .withProperty("spring.kafka.ssl.key-store-location", "file:/c/k.jks").withProperty("spring.kafka.ssl.key-store-password", "p")
                .withProperty("spring.kafka.ssl.key-password", "p");
        ok.setActiveProfiles("kafka-tls", "kafka-mtls");
        assertThatCode(() -> new KafkaTlsGuard(ok).validate()).doesNotThrowAnyException();

        MockEnvironment semKeystore = new MockEnvironment()
                .withProperty("spring.kafka.ssl.trust-store-location", "file:/c/t.jks").withProperty("spring.kafka.ssl.trust-store-password", "p");
        semKeystore.setActiveProfiles("kafka-tls", "kafka-mtls");
        assertThatThrownBy(() -> new KafkaTlsGuard(semKeystore).validate()).hasMessageContaining("KAFKA_SSL_KEYSTORE_LOCATION");
    }
}
