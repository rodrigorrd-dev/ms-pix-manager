package br.com.pixmanager.shared.model.document;

import br.com.pixmanager.shared.exception.InvalidDocumentException;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * CNPJ numérico <b>ou alfanumérico</b> (formato da Receita Federal; coexistem): 12 posições {@code [0-9A-Z]} +
 * 2 dígitos verificadores numéricos (RF-DOC-03, RN-DOC-05). O DV é sempre validado: módulo 11 com
 * {@code valor(c) = ASCII(c) − 48}. O caelum-stella usado no mesa-credito-full não cobre o formato alfanumérico.
 */
public record Cnpj(String value) implements Document {

    private static final Pattern SEPARATORS = Pattern.compile("[.\\-/\\s]");
    private static final Pattern SHAPE = Pattern.compile("[0-9A-Z]{12}[0-9]{2}");
    private static final int[] WEIGHTS_1 = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
    private static final int[] WEIGHTS_2 = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};

    public Cnpj {
        String normalized = normalize(value);
        if (!isValid(normalized)) {
            throw InvalidDocumentException.cnpj();
        }
        value = normalized;
    }

    public static Optional<Cnpj> tryParse(String raw) {
        String normalized = normalize(raw);
        return isValid(normalized) ? Optional.of(new Cnpj(normalized)) : Optional.empty();
    }

    public static Cnpj of(String raw) {
        return new Cnpj(raw);
    }

    public static boolean isValid(String raw) {
        String chars = normalize(raw);
        if (!SHAPE.matcher(chars).matches() || chars.chars().distinct().count() == 1) {
            return false;
        }
        int dv1 = checkDigit(chars.substring(0, 12), WEIGHTS_1);
        int dv2 = checkDigit(chars.substring(0, 12) + dv1, WEIGHTS_2);
        return chars.charAt(12) - '0' == dv1 && chars.charAt(13) - '0' == dv2;
    }

    @Override
    public String type() {
        return "CNPJ";
    }

    /** {@code **.ABC.345/01DE-**}: oculta o início e os dígitos verificadores. */
    @Override
    public String masked() {
        return "**." + value.substring(2, 5) + "." + value.substring(5, 8) + "/" + value.substring(8, 12) + "-**";
    }

    @Override
    public String toString() {
        return masked();
    }

    private static String normalize(String raw) {
        return raw == null ? "" : SEPARATORS.matcher(raw.trim()).replaceAll("").toUpperCase(java.util.Locale.ROOT);
    }

    private static int checkDigit(String base, int[] weights) {
        int sum = 0;
        for (int index = 0; index < base.length(); index++) {
            sum += (base.charAt(index) - '0') * weights[index];   // letras: 'A'-48 = 17 … 'Z'-48 = 42
        }
        int rest = sum % 11;
        return rest < 2 ? 0 : 11 - rest;
    }
}
