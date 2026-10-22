package br.com.pixmanager.api.messaging.receivedpix;

import br.com.pixmanager.core.port.in.receivedpix.ReceivePixUseCase;
import br.com.pixmanager.core.port.in.receivedpix.ReceivePixUseCase.ReceivePixResult;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.IntegrationException;
import br.com.pixmanager.support.integration.AbstractKafkaIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@DisplayName("Consumer — falha TÉCNICA: retry não bloqueante com backoff; esgotou ⇒ DLT")
class ReceivePixConsumerRetryIT extends AbstractKafkaIT {

    @MockitoBean ReceivePixUseCase receivePix;

    @BeforeEach
    void resetMock() {
        reset(receivePix);
    }

    String newEndToEndId() {
        return "E12345678202610151403" + UUID.randomUUID().toString().replace("-", "").substring(0, 11);
    }

    @Test
    @DisplayName("2 falhas técnicas e depois sucesso ⇒ 3 tentativas e NADA na DLT")
    void shouldRetryTechnicalFailureUntilItSucceeds() {
        AtomicInteger attempts = new AtomicInteger();
        when(receivePix.receive(any())).thenAnswer(invocation -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IntegrationException(ErrorCodes.DEPENDENCY_FAILURE, "banco indisponível", null);
            }
            return new ReceivePixResult.Duplicate();
        });
        String endToEndId = newEndToEndId();
        publishAvro(ReceivePixConsumer.TOPIC, endToEndId, settled("tech-" + UUID.randomUUID(), endToEndId, null, "5.00", "100001"));

        await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> assertThat(attempts.get()).isGreaterThanOrEqualTo(3));
        assertThat(readAll(ReceivePixConsumer.TOPIC + ".dlt", Duration.ofSeconds(2))).noneMatch(record -> endToEndId.equals(record.key()));
    }

    @Test
    @DisplayName("Falha técnica PERSISTENTE ⇒ esgota as tentativas configuradas e vai para a DLT")
    void shouldGoToDltAfterExhaustingAttempts() {
        AtomicInteger attempts = new AtomicInteger();
        when(receivePix.receive(any())).thenAnswer(invocation -> {
            attempts.incrementAndGet();
            throw new IntegrationException(ErrorCodes.DEPENDENCY_FAILURE, "sempre falha", null);
        });
        String endToEndId = newEndToEndId();
        publishAvro(ReceivePixConsumer.TOPIC, endToEndId, settled("tech-dlt-" + UUID.randomUUID(), endToEndId, null, "5.00", "100001"));

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(readAll(ReceivePixConsumer.TOPIC + ".dlt", Duration.ofSeconds(2))).anyMatch(record -> endToEndId.equals(record.key())));
        assertThat(attempts.get()).as("pix.kafka.retry.attempts=3 no perfil de teste").isEqualTo(3);
    }
}
