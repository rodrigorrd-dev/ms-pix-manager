package br.com.pixmanager.shared.exception;

/**
 * Falha <b>técnica</b> em dependência externa (timeout, 5xx, 429, circuito aberto): candidata a retry com backoff.
 * Não herda de {@link BusinessException} de propósito.
 */
public class IntegrationException extends RuntimeException {

    private final String code;

    public IntegrationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
