package br.com.pixmanager.core.domain.entity;

import java.time.Duration;

/** Backoff exponencial limitado com jitter. Padrão: 8 tentativas, base 5s, teto 6h (docs/10 rodada 1). */
public record WebhookRetryPolicy(int maxAttempts, Duration base, Duration cap, double jitterRatio) {

    public static WebhookRetryPolicy standard() {
        return new WebhookRetryPolicy(8, Duration.ofSeconds(5), Duration.ofHours(6), 0.2);
    }

    public boolean exhausted(int attemptsMade) {
        return attemptsMade >= maxAttempts;
    }

    /**
     * Espera antes da próxima tentativa após {@code attemptsMade} falhas: {@code min(cap, base·6^(n-1))} ± jitter.
     * Multiplicador 6: 5s, 30s, 3min, 18min, 1h48, 6h(teto)… O jitter nunca ultrapassa o teto.
     * @param random01 número em [0,1) injetado (testável).
     */
    public Duration delayAfter(int attemptsMade, double random01) {
        double exp = Math.min((double) cap.toMillis(), base.toMillis() * Math.pow(6, Math.max(attemptsMade, 1) - 1));
        double factor = 1 + jitterRatio * (2 * Math.min(Math.max(random01, 0), 1) - 1);
        return Duration.ofMillis(Math.min(cap.toMillis(), Math.round(exp * factor)));
    }
}
