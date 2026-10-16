package br.com.pixmanager.shared.exception;

import br.com.pixmanager.shared.constant.ErrorCodes;

/** {@code If-Match} não confere com a versão atual do agregado (412). */
public class PreconditionFailedException extends BusinessException {

    public PreconditionFailedException(String message) {
        super(ErrorCodes.OUTDATED_VERSION, message);
    }
}
