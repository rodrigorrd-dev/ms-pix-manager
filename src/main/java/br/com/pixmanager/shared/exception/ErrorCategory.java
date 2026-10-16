package br.com.pixmanager.shared.exception;

/**
 * A natureza de uma falha, independente de protocolo. Cada adaptador de entrada traduz a categoria com um
 * {@code switch} exaustivo: categoria nova quebra a compilação até ser tratada (padrão do mesa-credito-full).
 */
public enum ErrorCategory {

    BAD_REQUEST(400),
    UNAUTHENTICATED(401),
    FORBIDDEN(403),
    NOT_FOUND(404),
    CONFLICT(409),
    PRECONDITION_FAILED(412),
    UNPROCESSABLE_ENTITY(422),
    PRECONDITION_REQUIRED(428),
    TOO_MANY_REQUESTS(429),
    INTERNAL_ERROR(500),
    BAD_GATEWAY(502),
    SERVICE_UNAVAILABLE(503);

    private final int httpStatus;

    ErrorCategory(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /** Falha do lado do serviço (ou de integração dele), não de quem chamou. */
    public boolean isServerSide() {
        return httpStatus >= 500;
    }
}
