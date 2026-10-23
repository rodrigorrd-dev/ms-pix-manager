package br.com.pixmanager.core.domain.entity;

import br.com.pixmanager.core.domain.enums.PixKeyType;

import br.com.pixmanager.shared.model.document.Cnpj;
import br.com.pixmanager.shared.model.document.Cpf;
import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.shared.constant.ErrorCodes;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Valor de chave Pix validado por tipo (DICT 2.12.1, BR-KEY-01): CPF 11 dígitos (com DV), CNPJ 14 alfanuméricos (com DV),
 * PHONE E.164 ({@code +} + dígito ≠ 0, ≤ 15 dígitos), EMAIL minúsculo ≤ 77, EVP UUID minúsculo 8-4-4-4-12. Máx. 77 caracteres.
 */
public record PixKeyValue(PixKeyType type, String value) {

    public static final int MAX_LENGTH = 77;
    private static final Pattern PHONE = Pattern.compile("\\+[1-9]\\d{1,14}");
    private static final Pattern EMAIL = Pattern.compile("[a-z0-9._%+\\-]+@[a-z0-9\\-]+(\\.[a-z0-9\\-]+)*\\.[a-z]{2,}");
    private static final Pattern EVP = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    public PixKeyValue {
        if (type == null) {
            throw invalid();
        }
        String normalized = normalize(type, value);
        if (normalized == null) {
            throw invalid();
        }
        value = normalized;
    }

    public static PixKeyValue of(PixKeyType type, String raw) {
        return new PixKeyValue(type, raw);
    }

    public static Optional<PixKeyValue> tryParse(PixKeyType type, String raw) {
        if (type == null || normalize(type, raw) == null) {
            return Optional.empty();
        }
        return Optional.of(new PixKeyValue(type, raw));
    }

    /**
     * Infere o tipo pelo formato (a API Pix do BCB recebe só {@code chave}, sem tipo): {@code +…} ⇒ PHONE; {@code @} ⇒ EMAIL;
     * UUID ⇒ EVP; CPF válido ⇒ CPF; CNPJ válido ⇒ CNPJ. Ambiguidade não existe: os formatos são disjuntos.
     */
    public static Optional<PixKeyValue> infer(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        PixKeyType type;
        if (trimmed.startsWith("+")) {
            type = PixKeyType.PHONE;
        } else if (trimmed.contains("@")) {
            type = PixKeyType.EMAIL;
        } else if (trimmed.contains("-") && trimmed.length() == 36) {
            type = PixKeyType.EVP;
        } else if (Cpf.tryParse(trimmed).isPresent()) {
            type = PixKeyType.CPF;
        } else if (Cnpj.tryParse(trimmed).isPresent()) {
            type = PixKeyType.CNPJ;
        } else {
            return Optional.empty();
        }
        return tryParse(type, trimmed);
    }

    /** {@code null} = inválido (sem exceção, sem eco do valor). */
    private static String normalize(PixKeyType type, String raw) {
        if (raw == null || raw.isBlank() || raw.length() > MAX_LENGTH + 8) {
            return null;
        }
        String trimmed = raw.trim();
        String normalized = switch (type) {
            case CPF -> Cpf.tryParse(trimmed).map(Cpf::value).orElse(null);
            case CNPJ -> Cnpj.tryParse(trimmed).map(Cnpj::value).orElse(null);
            case PHONE -> PHONE.matcher(trimmed).matches() ? trimmed : null;
            case EMAIL -> EMAIL.matcher(trimmed).matches() && trimmed.equals(trimmed.toLowerCase(Locale.ROOT)) ? trimmed : null;
            case EVP -> EVP.matcher(trimmed).matches() ? trimmed : null;
        };
        return normalized != null && normalized.length() <= MAX_LENGTH ? normalized : null;
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCodes.INVALID_PIX_KEY, "A chave Pix informada é inválida para o tipo.");
    }

    /** Máscara segura para exibição/log. */
    public String masked() {
        return switch (type) {
            case CPF -> Cpf.of(value).masked();
            case CNPJ -> Cnpj.of(value).masked();
            case PHONE -> value.substring(0, Math.min(3, value.length())) + "*".repeat(Math.max(0, value.length() - 5))
                    + value.substring(value.length() - 2);
            case EMAIL -> value.charAt(0) + "***" + value.substring(value.indexOf('@'));
            case EVP -> value.substring(0, 8) + "-****-****-****-" + value.substring(24);
        };
    }

    @Override
    public String toString() {
        return type + ":" + masked();
    }
}
