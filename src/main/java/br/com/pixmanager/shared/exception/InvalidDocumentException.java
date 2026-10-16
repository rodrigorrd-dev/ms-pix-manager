package br.com.pixmanager.shared.exception;

import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.shared.constant.ErrorCodes;

/** CPF/CNPJ inválido. A mensagem NUNCA ecoa o valor informado (PII). */
public class InvalidDocumentException extends BusinessException {

    private InvalidDocumentException(String code, String message) {
        super(code, message);
    }

    public static InvalidDocumentException cpf() {
        return new InvalidDocumentException(ErrorCodes.INVALID_CPF, "O CPF informado é inválido.");
    }

    public static InvalidDocumentException cnpj() {
        return new InvalidDocumentException(ErrorCodes.INVALID_CNPJ, "O CNPJ informado é inválido.");
    }

    public static InvalidDocumentException document() {
        return new InvalidDocumentException(ErrorCodes.INVALID_CPF, "O documento (CPF/CNPJ) informado é inválido.");
    }
}
