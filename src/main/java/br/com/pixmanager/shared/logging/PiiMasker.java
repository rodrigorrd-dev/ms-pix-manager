package br.com.pixmanager.shared.logging;

import br.com.pixmanager.shared.model.document.Cnpj;
import br.com.pixmanager.shared.model.document.Cpf;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mascara CPF/CNPJ embutidos em texto livre (defesa em profundidade nos appenders de log). Só mascara candidato que
 * <b>realmente é</b> um documento válido: reduz falso positivo em números quaisquer (telefone, valor, timestamp) —
 * evolução do {@code MaskUtils} do mesa-credito-full, que mascarava qualquer sequência com cara de documento.
 */
public final class PiiMasker {

    private static final Pattern CNPJ = Pattern.compile(
            "(?<![0-9A-Za-z])[0-9A-Z]{2}\\.?[0-9A-Z]{3}\\.?[0-9A-Z]{3}/?[0-9A-Z]{4}-?[0-9]{2}(?![0-9A-Za-z])");
    private static final Pattern CPF = Pattern.compile(
            "(?<![0-9A-Za-z])\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2}(?![0-9A-Za-z])");

    private PiiMasker() {}

    public static String maskText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String withoutCnpj = replace(CNPJ, text, candidate -> Cnpj.tryParse(candidate).map(Cnpj::masked).orElse(null));
        return replace(CPF, withoutCnpj, candidate -> Cpf.tryParse(candidate).map(Cpf::masked).orElse(null));
    }

    private static String replace(Pattern pattern, String text, java.util.function.Function<String, String> masker) {
        Matcher matcher = pattern.matcher(text);
        return matcher.replaceAll(result -> {
            String masked = masker.apply(result.group());
            return Matcher.quoteReplacement(masked != null ? masked : result.group());
        });
    }
}
