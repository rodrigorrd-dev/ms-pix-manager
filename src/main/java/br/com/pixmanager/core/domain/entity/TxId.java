package br.com.pixmanager.core.domain.entity;

import br.com.pixmanager.shared.exception.BadRequestException;
import br.com.pixmanager.shared.constant.ErrorCodes;

import java.util.regex.Pattern;

/** {@code txid} da cobrança: {@code [a-zA-Z0-9]{26,35}} (API Pix 2.10.0). Único por DOCUMENTO do recebedor, não global. */
public record TxId(String value) {

    private static final Pattern PATTERN = Pattern.compile("[a-zA-Z0-9]{26,35}");

    public TxId {
        if (value == null || !PATTERN.matcher(value).matches()) {
            throw new BadRequestException(ErrorCodes.INVALID_PAYLOAD, "txid inválido: esperado [a-zA-Z0-9]{26,35}.");
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
