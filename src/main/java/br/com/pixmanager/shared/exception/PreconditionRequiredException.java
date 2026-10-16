package br.com.pixmanager.shared.exception;

import br.com.pixmanager.shared.constant.ErrorCodes;

/** Operação que exige {@code If-Match} chegou sem ele (428, RFC 6585). */
public class PreconditionRequiredException extends BusinessException {

    public PreconditionRequiredException(String message) {
        super(ErrorCodes.PRECONDITION_REQUIRED, message);
    }
}
