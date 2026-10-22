package br.com.pixmanager.shared.model.document;

import br.com.pixmanager.shared.exception.InvalidDocumentException;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * CPF válido (RF-DOC-01, RN-DOC-01): 11 dígitos, dígitos verificadores módulo 11, sem sequência de dígitos repetidos.
 * Aceita máscara na entrada; guarda só os dígitos. {@link #toString()} devolve a forma MASCARADA — o valor completo
 * só sai por {@link #value()}, de propósito (logs e eventos nunca imprimem o CPF por acidente).
 */
public record Cpf(String value) implements Document {

    private static final Pattern NON_DIGIT = Pattern.compile("[.\\-\\s]");
    private static final Pattern DIGITS_11 = Pattern.compile("\\d{11}");

    public Cpf {
        String normalized = normalize(value);
        if (!isValid(normalized)) {
            throw InvalidDocumentException.cpf();
        }
        value = normalized;
    }

    /** Falha esperada como valor (code-design): {@code Optional.empty()} em vez de exceção. */
    public static Optional<Cpf> tryParse(String raw) {
        String normalized = normalize(raw);
        return isValid(normalized) ? Optional.of(new Cpf(normalized)) : Optional.empty();
    }

    public static Cpf of(String raw) {
        return new Cpf(raw);
    }

    public static boolean isValid(String raw) {
        String digits = normalize(raw);
        if (!DIGITS_11.matcher(digits).matches() || allSame(digits)) {
            return false;
        }
        return digit(digits, 9) == check(digits, 9) && digit(digits, 10) == check(digits, 10);
    }

    @Override
    public String type() {
        return "CPF";
    }

    /** {@code ***.982.247-**} */
    @Override
    public String masked() {
        return "***." + value.substring(3, 6) + "." + value.substring(6, 9) + "-**";
    }

    @Override
    public String toString() {
        return masked();
    }

    private static String normalize(String raw) {
        return raw == null ? "" : NON_DIGIT.matcher(raw.trim()).replaceAll("");
    }

    private static boolean allSame(String digits) {
        return digits.chars().distinct().count() == 1;
    }

    private static int digit(String digits, int index) {
        return digits.charAt(index) - '0';
    }

    /** DV na posição {@code n} (9 ou 10): soma ponderada dos {@code n} primeiros dígitos, pesos {@code n+1..2}. */
    private static int check(String digits, int digitCount) {
        int sum = 0;
        for (int index = 0; index < digitCount; index++) {
            sum += digit(digits, index) * (digitCount + 1 - index);
        }
        return (sum * 10) % 11 % 10;
    }
}
