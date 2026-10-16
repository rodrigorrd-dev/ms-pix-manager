package br.com.pixmanager.shared.exception;

import java.time.Duration;

/** Excesso de pedidos (429). Carrega quanto esperar: vira o cabeçalho {@code Retry-After}. Negócio: definitivo até o prazo passar. */
public class TooManyRequestsException extends BusinessException {

    private final Duration retryAfter;

    public TooManyRequestsException(String code, String message, Duration retryAfter) {
        super(code, message);
        this.retryAfter = retryAfter == null || retryAfter.isNegative() ? Duration.ofSeconds(1) : retryAfter;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}
