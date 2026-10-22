package br.com.pixmanager.core.domain.entity;

import br.com.pixmanager.core.domain.enums.DeliveryStatus;
import br.com.pixmanager.shared.exception.InvalidTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Webhook — backoff com jitter, DEAD e reenvio manual")
class WebhookDeliveryTest {

    static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    WebhookRetryPolicy policy = WebhookRetryPolicy.standard();

    WebhookDelivery fresh() {
        return WebhookDelivery.pending(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "PixReceived", NOW);
    }

    @Test
    void backoffGrowsAndNeverExceedsTheCap() {
        assertThat(policy.delayAfter(1, 0.5)).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.delayAfter(2, 0.5)).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.delayAfter(3, 0.5)).isEqualTo(Duration.ofMinutes(3));
        for (int attempt = 1; attempt <= 20; attempt++) {
            assertThat(policy.delayAfter(attempt, 1.0)).isLessThanOrEqualTo(Duration.ofHours(6));
            assertThat(policy.delayAfter(attempt, 0.0)).isPositive();
        }
        assertThat(policy.delayAfter(10, 0.5)).isEqualTo(Duration.ofHours(6));
    }

    @Test
    void jitterStaysWithinTwentyPercent() {
        assertThat(policy.delayAfter(2, 0.0)).isEqualTo(Duration.ofMillis(24_000));
        assertThat(policy.delayAfter(2, 1.0)).isEqualTo(Duration.ofMillis(36_000));
    }

    @Test
    @DisplayName("8 falhas ⇒ DEAD; antes disso RETRYING com próxima tentativa no futuro")
    void exhaustingAttemptsKillsTheDelivery() {
        WebhookDelivery delivery = fresh();
        for (int index = 1; index < 8; index++) {
            delivery = delivery.failed(500, "boom", NOW, policy, 0.5);
            assertThat(delivery.status()).isEqualTo(DeliveryStatus.RETRYING);
            assertThat(delivery.nextAttemptAt()).isAfter(NOW);
            assertThat(delivery.attempts()).isEqualTo(index);
        }
        delivery = delivery.failed(503, "x".repeat(900), NOW, policy, 0.5);
        assertThat(delivery.status()).isEqualTo(DeliveryStatus.DEAD);
        assertThat(delivery.attempts()).isEqualTo(8);
        assertThat(delivery.lastError()).hasSize(500);
        assertThat(delivery.isDue(NOW.plusSeconds(999_999))).isFalse();
    }

    @Test
    void successIsFinalAndCountsTheAttempt() {
        WebhookDelivery ok = fresh().failed(502, "e", NOW, policy, 0.5).delivered(204, NOW.plusSeconds(10));
        assertThat(ok.status()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(ok.attempts()).isEqualTo(2);
        assertThat(ok.deliveredAt()).isEqualTo(NOW.plusSeconds(10));
        assertThatThrownBy(() -> ok.failed(500, "x", NOW, policy, 0.1)).isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void dueOnlyWhenTheTimeHasCome() {
        WebhookDelivery delivery = fresh().failed(500, "e", NOW, policy, 0.5);
        assertThat(delivery.isDue(NOW)).isFalse();
        assertThat(delivery.isDue(NOW.plusSeconds(6))).isTrue();
    }

    @Test
    void manualRequeueOnlyFromDead() {
        WebhookDelivery dead = fresh();
        for (int index = 0; index < 8; index++) {
            dead = dead.failed(500, "e", NOW, policy, 0.5);
        }
        WebhookDelivery again = dead.requeued(NOW.plusSeconds(60));
        assertThat(again.status()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(again.attempts()).isZero();
        assertThat(again.isDue(NOW.plusSeconds(60))).isTrue();
        assertThatThrownBy(() -> fresh().requeued(NOW)).isInstanceOf(InvalidTransitionException.class);
    }
}
