package br.com.pixmanager.shared.util;

/**
 * {@code If-None-Match} (RFC 9110 §13.1.2): aceita {@code *}, lista separada por vírgula e validadores fracos ({@code W/"1"}).
 * Para GET, a comparação é fraca. A versão do agregado é o ETag, sempre entre aspas.
 */
public final class ETags {

    private ETags() {}

    /** Valor do ETag como vai no cabeçalho: {@code "<versão>"}. */
    public static String of(long version) {
        return "\"" + version + "\"";
    }

    /** {@code true} se o cliente já tem a representação atual (⇒ responder 304). Cabeçalho ausente ⇒ {@code false}. */
    public static boolean noneMatch(String ifNoneMatch, long currentVersion) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        String header = ifNoneMatch.trim();
        if ("*".equals(header)) {
            return true;
        }
        String current = of(currentVersion);
        for (String candidate : header.split(",")) {
            String tag = candidate.trim();
            if (tag.startsWith("W/")) {
                tag = tag.substring(2);
            }
            if (tag.equals(current)) {
                return true;
            }
        }
        return false;
    }
}
