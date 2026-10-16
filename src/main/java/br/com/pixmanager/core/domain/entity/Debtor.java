package br.com.pixmanager.core.domain.entity;

import br.com.pixmanager.shared.model.document.Document;
import br.com.pixmanager.shared.exception.BadRequestException;
import br.com.pixmanager.shared.constant.ErrorCodes;

/** Devedor opcional da cobrança: {@code cpf} XOR {@code cnpj} (BR-COB-05), validados por dígito verificador. */
public record Debtor(Document document, String name) {

    public Debtor {
        if (document == null) {
            throw new BadRequestException(ErrorCodes.INVALID_PAYLOAD, "O devedor exige cpf ou cnpj.");
        }
        if (name == null || name.isBlank() || name.length() > 200) {
            throw new BadRequestException(ErrorCodes.INVALID_PAYLOAD, "O nome do devedor é obrigatório (até 200 caracteres).");
        }
    }
}
