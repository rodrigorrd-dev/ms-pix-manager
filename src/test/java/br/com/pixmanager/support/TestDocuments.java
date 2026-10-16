package br.com.pixmanager.support;

import java.security.SecureRandom;

/** CPF/CNPJ VÁLIDOS e únicos para testes (o índice cego do documento do titular é único no banco). */
public final class TestDocuments {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TestDocuments() {}

    public static String randomCpf() {
        while (true) {
            int[] d = new int[11];
            for (int i = 0; i < 9; i++) {
                d[i] = RANDOM.nextInt(10);
            }
            d[9] = check(d, 9);
            d[10] = check(d, 10);
            StringBuilder sb = new StringBuilder();
            for (int digit : d) {
                sb.append(digit);
            }
            String cpf = sb.toString();
            if (cpf.chars().distinct().count() > 1) {
                return cpf;
            }
        }
    }

    private static int check(int[] d, int n) {
        int sum = 0;
        for (int i = 0; i < n; i++) {
            sum += d[i] * (n + 1 - i);
        }
        return (sum * 10) % 11 % 10;
    }

    /** CNPJ alfanumérico válido ({@code 12} aleatórios [0-9A-Z] + 2 DV). */
    public static String randomCnpj() {
        String alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        StringBuilder base = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            base.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        int d1 = dv(base.toString(), new int[]{5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2});
        int d2 = dv(base.toString() + d1, new int[]{6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2});
        return base.toString() + d1 + d2;
    }

    private static int dv(String s, int[] w) {
        int sum = 0;
        for (int i = 0; i < s.length(); i++) {
            sum += (s.charAt(i) - 48) * w[i];
        }
        int r = sum % 11;
        return r < 2 ? 0 : 11 - r;
    }
}
