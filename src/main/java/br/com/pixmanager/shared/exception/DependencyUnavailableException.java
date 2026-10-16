package br.com.pixmanager.shared.exception;

import br.com.pixmanager.shared.constant.ErrorCodes;

/** Dependência indisponível (circuito aberto, bulkhead cheio, 429 do gateway): 503 + {@code Retry-After}. */
public class DependencyUnavailableException extends IntegrationException {

    public DependencyUnavailableException(String message, Throwable cause) {
        super(ErrorCodes.DEPENDENCY_UNAVAILABLE, message, cause);
    }
}
