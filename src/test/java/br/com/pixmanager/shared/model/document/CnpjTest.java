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
                .satisfies(exception -> assertThat(((InvalidDocumentException) exception).getCode()).isEqualTo(ErrorCodes.INVALID_CNPJ));
    }

    /** Propriedade: qualquer base alfanumérica de 12 posições com os DV calculados pelo mesmo algoritmo deve validar. */
    @Test
    void generatedAlphanumericCnpjsShouldAllValidate() {
        SecureRandom random = new SecureRandom();
        String alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        int[] firstWeights = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        int[] secondWeights = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        for (int iteration = 0; iteration < 5_000; iteration++) {
            StringBuilder base = new StringBuilder();
            for (int index = 0; index < 12; index++) {
                base.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            if (base.chars().distinct().count() == 1) {
                continue;
            }
            int firstCheckDigit = dv(base.toString(), firstWeights);
            int secondCheckDigit = dv(base.toString() + firstCheckDigit, secondWeights);
            assertThat(Cnpj.isValid(base.toString() + firstCheckDigit + secondCheckDigit)).as(base.toString()).isTrue();
            assertThat(Cnpj.isValid(base.toString() + firstCheckDigit + ((secondCheckDigit + 1) % 10))).isFalse();
        }
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
