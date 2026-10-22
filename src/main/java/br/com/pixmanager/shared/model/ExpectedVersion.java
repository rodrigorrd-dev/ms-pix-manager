package br.com.pixmanager.shared.model;

import br.com.pixmanager.shared.exception.BadRequestException;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.PreconditionRequiredException;

/**
 * {@code If-Match} de um agregado (RFC 9110). Ausente ⇒ 428 (nunca last-write-wins silencioso); malformado ⇒ 400 (nunca
 * "sem header": fail-open do mesa-credito-full); {@code *} ⇒ qualquer versão existente.
 */
public sealed interface ExpectedVersion {

    record Exact(long version) implements ExpectedVersion {}

    record Any() implements ExpectedVersion {}

    static ExpectedVersion parse(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new PreconditionRequiredException("O cabeçalho If-Match é obrigatório para esta operação.");
        }
        String value = ifMatch.trim();
        if ("*".equals(value)) {
            return new Any();
        }
        String unquoted = value.startsWith("W/") ? value.substring(2) : value;
        if (unquoted.length() >= 2 && unquoted.startsWith("\"") && unquoted.endsWith("\"")) {
            unquoted = unquoted.substring(1, unquoted.length() - 1);
        }
        try {
            long version = Long.parseLong(unquoted);
            if (version < 0) {
                throw new NumberFormatException();
            }
            return new Exact(version);
        } catch (NumberFormatException exception) {
            throw new BadRequestException(ErrorCodes.INVALID_PRECONDITION, "If-Match malformado.");
        }
    }

    default boolean matches(long currentVersion) {
        return switch (this) {
            case Any any -> true;
            case Exact exact -> exact.version() == currentVersion;
        };
    }
}
