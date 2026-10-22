package br.com.pixmanager.shared.util;

/** Utilitário de texto. */
public final class Text {

    private Text() {}

    /** Corta em {@code maxLength} caracteres (colunas com tamanho fixo); {@code null} continua {@code null}. */
    public static String truncate(String text, int maxLength) {
        return text == null ? null : text.substring(0, Math.min(maxLength, text.length()));
    }
}
