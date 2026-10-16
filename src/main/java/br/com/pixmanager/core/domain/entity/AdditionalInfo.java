package br.com.pixmanager.core.domain.entity;

import br.com.pixmanager.shared.exception.BadRequestException;
import br.com.pixmanager.shared.constant.ErrorCodes;

import java.util.List;

/** {@code infoAdicionais}: nome ≤ 50, valor ≤ 200, até 50 itens. */
public record AdditionalInfo(String name, String value) {

    public static final int MAX_ITEMS = 50;

    public AdditionalInfo {
        if (name == null || name.isBlank() || name.length() > 50 || value == null || value.isBlank() || value.length() > 200) {
            throw new BadRequestException(ErrorCodes.INVALID_PAYLOAD, "infoAdicionais inválido (nome ≤ 50, valor ≤ 200).");
        }
    }

    public static List<AdditionalInfo> validated(List<AdditionalInfo> items) {
        List<AdditionalInfo> list = items == null ? List.of() : List.copyOf(items);
        if (list.size() > MAX_ITEMS) {
            throw new BadRequestException(ErrorCodes.INVALID_PAYLOAD, "infoAdicionais aceita no máximo 50 itens.");
        }
        return list;
    }
}
