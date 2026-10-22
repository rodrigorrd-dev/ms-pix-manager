package br.com.pixmanager.support;

import java.security.SecureRandom;

/** CPF/CNPJ VÁLIDOS e únicos para testes (o índice cego do documento do titular é único no banco). */
public final class TestDocuments {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TestDocuments() {}

    public static String randomCpf() {
        while (true) {
            int[] digits = new int[11];
            for (int index = 0; index < 9; index++) {
                digits[index] = RANDOM.nextInt(10);
            }
            digits[9] = check(digits, 9);
            digits[10] = check(digits, 10);
            StringBuilder builder = new StringBuilder();
            for (int digit : digits) {
                builder.append(digit);
            }
            String cpf = builder.toString();
            if (cpf.chars().distinct().count() > 1) {
                return cpf;
            }
        }
    }

    private static int check(int[] digits, int position) {
        int sum = 0;
        for (int index = 0; index < position; index++) {
            sum += digits[index] * (position + 1 - index);
        }
        return (sum * 10) % 11 % 10;
    }

    /** CNPJ alfanumérico válido ({@code 12} aleatórios [0-9A-Z] + 2 DV). */
    public static String randomCnpj() {
        String alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        StringBuilder base = new StringBuilder();
        for (int index = 0; index < 12; index++) {
            base.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        int firstCheckDigit = dv(base.toString(), new int[]{5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2});
        int secondCheckDigit = dv(base.toString() + firstCheckDigit, new int[]{6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2});
        return base.toString() + firstCheckDigit + secondCheckDigit;
    }

    private static int dv(String digits, int[] weights) {
        int sum = 0;
        for (int index = 0; index < digits.length(); index++) {
            sum += (digits.charAt(index) - 48) * weights[index];
        }
        int remainder = sum % 11;
        return remainder < 2 ? 0 : 11 - remainder;
    }
}
