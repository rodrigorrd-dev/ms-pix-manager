package br.com.pixmanager.shared.exception;

/**
 * Erro de <b>negócio</b>: definitivo, nunca retentado. É IRMÃ (não mãe) de {@link IntegrationException}: um
 * {@code catch (BusinessException)} jamais engole falha técnica (lição da skill resilience-patterns).
 */
public class BusinessException extends RuntimeException {

    private final String code;

    public BusinessException(String code, String message) {
        // desfecho ESPERADO (404/409/422/400, recusa do DICT): sem stack trace, que ninguém lê e custa a cada ocorrência
        super(message, null, false, false);
        this.code = code;
    }

    public BusinessException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
