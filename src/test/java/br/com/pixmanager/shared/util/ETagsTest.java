package br.com.pixmanager.shared.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ETagsTest {

    @Test
    @DisplayName("o ETag é a versão entre aspas")
    void formato() {
        assertThat(ETags.of(7)).isEqualTo("\"7\"");
    }

    @ParameterizedTest(name = "If-None-Match [{0}] contra versão 3 ⇒ 304? {1}")
    @DisplayName("If-None-Match: *, lista, validador fraco, versão antiga e ausente")
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            "\"3\"|true", "W/\"3\"|true", "*|true", "\"1\", \"3\" , \"9\"|true", "\"1\", W/\"3\"|true",
            "\"2\"|false", "3|false", "\"3x\"|false", "''|false", "NULL|false"})
    void comparacao(String header, boolean esperado) {
        assertThat(ETags.noneMatch("''".equals(header) ? "" : header, 3)).isEqualTo(esperado);
    }
}
