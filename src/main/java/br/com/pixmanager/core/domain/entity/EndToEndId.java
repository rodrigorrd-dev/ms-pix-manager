package br.com.pixmanager.core.domain.entity;

import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.BusinessException;

import java.util.regex.Pattern;

/** {@code endToEndId} (SPI): exatamente 32 caracteres {@code [a-zA-Z0-9]}. Chave de idempotência do recebimento. */
public record EndToEndId(String value) {

    private static final Pattern PATTERN = Pattern.compile("[a-zA-Z0-9]{32}");

    public EndToEndId {
        if (value == null || !PATTERN.matcher(value).matches()) {
            throw new BusinessException(ErrorCodes.INVALID_VALUE_FORMAT, "endToEndId inválido: esperado 32 caracteres [a-zA-Z0-9].");
        }
    }

    public static boolean isValid(String raw) {
        return raw != null && PATTERN.matcher(raw).matches();
    }

    @Override
    public String toString() {
        return value;
    }
}
