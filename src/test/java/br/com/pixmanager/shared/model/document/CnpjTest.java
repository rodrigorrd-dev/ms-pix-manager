package br.com.pixmanager.shared.model.document;

import br.com.pixmanager.shared.exception.InvalidDocumentException;

import br.com.pixmanager.shared.constant.ErrorCodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FR-DOC-03 / BR-DOC-05 — CNPJ numérico e alfanumérico")
class CnpjTest {

    @ParameterizedTest
    @ValueSource(strings = {"12.ABC.345/01DE-35", "12ABC34501DE35", "12abc34501de35", "11.222.333/0001-81", "11222333000181"})
    void shouldAcceptValidNumericAndAlphanumericCnpj(String raw) {
        assertThat(Cnpj.tryParse(raw)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"12ABC34501DE36", "12ABC34501DE3A", "00000000000000", "AAAAAAAAAAAAAA", "11222333000180",
            "12ABC34501DE3", "12ABC34501DE355", "12.ABC.345/01DE-3!"})
    @NullAndEmptySource
    void shouldRejectInvalidCnpj(String raw) {
        assertThat(Cnpj.tryParse(raw)).isEmpty();
    }

    @Test
    void shouldNormalizeToUppercaseWithoutSeparators() {
        assertThat(Cnpj.of("12.abc.345/01de-35").value()).isEqualTo("12ABC34501DE35");
    }

    @Test
    void shouldMask() {
        assertThat(Cnpj.of("12ABC34501DE35").masked()).isEqualTo("**.ABC.345/01DE-**");
    }

    @Test
    void shouldFailWithCnpjCodeAndNotEchoValue() {
        assertThatThrownBy(() -> Cnpj.of("12ABC34501DE36"))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageNotContaining("12ABC")
                .satisfies(e -> assertThat(((InvalidDocumentException) e).getCode()).isEqualTo(ErrorCodes.INVALID_CNPJ));
    }

    /** Propriedade: qualquer base alfanumérica de 12 posições com os DV calculados pelo mesmo algoritmo deve validar. */
    @Test
    void generatedAlphanumericCnpjsShouldAllValidate() {
        SecureRandom random = new SecureRandom();
        String alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        int[] w1 = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        int[] w2 = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        for (int n = 0; n < 5_000; n++) {
            StringBuilder base = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                base.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            if (base.chars().distinct().count() == 1) {
                continue;
            }
            int d1 = dv(base.toString(), w1);
            int d2 = dv(base.toString() + d1, w2);
            assertThat(Cnpj.isValid(base.toString() + d1 + d2)).as(base.toString()).isTrue();
            assertThat(Cnpj.isValid(base.toString() + d1 + ((d2 + 1) % 10))).isFalse();
        }
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
