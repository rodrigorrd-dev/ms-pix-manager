package br.com.pixmanager.shared.constant;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Literal {@code "PIX-ddd-ddd"} fora de {@link ErrorCodes} é contrato não documentado (lição do mesa-credito-full). */
class ErrorCodeCatalogTest {

    private static final Pattern LITERAL = Pattern.compile("\"PIX-\\d{3}-\\d{3}\"");

    @Test
    void errorCodeLiteralsMustLiveOnlyInCatalog() throws IOException {
        List<String> offenders;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            offenders = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.endsWith("ErrorCodes.java"))
                    .filter(path -> {
                        try {
                            return LITERAL.matcher(Files.readString(path)).find();
                        } catch (IOException exception) {
                            throw new IllegalStateException(exception);
                        }
                    })
                    .map(Path::toString).toList();
        }
        assertThat(offenders).as("arquivos com literal PIX-xxx-xxx fora de ErrorCodes").isEmpty();
    }

    @Test
    void codesShouldBeUniqueAndWellFormed() throws IllegalAccessException {
        var seen = new java.util.HashSet<String>();
        for (var f : ErrorCodes.class.getDeclaredFields()) {
            if (f.getType() == String.class) {
                String code = (String) f.get(null);
                assertThat(code).matches("PIX-\\d{3}-\\d{3}");
                assertThat(seen.add(code)).as("duplicado: " + code).isTrue();
            }
        }
    }
}
