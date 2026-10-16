package br.com.pixmanager.shared.model.document;

import br.com.pixmanager.shared.exception.InvalidDocumentException;

import br.com.pixmanager.shared.constant.ErrorCodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FR-DOC-01..02 / BR-DOC-01..03 — CPF")
class CpfTest {

    @ParameterizedTest
    @ValueSource(strings = {"529.982.247-25", "52998224725", "11144477735", "111.444.777-35", " 529.982.247-25 "})
    void shouldAcceptValidCpfWithOrWithoutMask(String raw) {
        assertThat(Cpf.tryParse(raw)).isPresent();
        assertThat(Cpf.isValid(raw)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"111.111.111-11", "00000000000", "99999999999", "529.982.247-24", "52998224724",
            "1234567890", "123456789012", "5299822472A", "abc", "529 982 247 25 1"})
    @NullAndEmptySource
    void shouldRejectInvalidCpf(String raw) {
        assertThat(Cpf.tryParse(raw)).isEmpty();
        assertThat(Cpf.isValid(raw)).isFalse();
    }

    @Test
    void shouldNormalizeToDigitsOnly() {
        assertThat(Cpf.of("529.982.247-25").value()).isEqualTo("52998224725");
    }

    @Test
    void shouldMaskCpf() {
        assertThat(Cpf.of("52998224725").masked()).isEqualTo("***.982.247-**");
    }

    @Test
    @DisplayName("BR-DOC-03: toString nunca expõe o documento completo")
    void toStringMustBeMasked() {
        Cpf cpf = Cpf.of("52998224725");
        assertThat(cpf.toString()).isEqualTo("***.982.247-**").doesNotContain("52998224725");
        assertThat(("log: " + cpf)).doesNotContain("529.982.247-25").doesNotContain("52998224725");
    }

    @Test
    @DisplayName("BR-DOC-01: erro não ecoa o valor informado e usa PIX-422-001")
    void shouldFailWithoutEchoingValue() {
        assertThatThrownBy(() -> Cpf.of("111.111.111-11"))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageNotContaining("111")
                .satisfies(e -> assertThat(((InvalidDocumentException) e).getCode()).isEqualTo(ErrorCodes.INVALID_CPF));
    }

    @Test
    void equalCpfsShouldBeEqualRegardlessOfMask() {
        assertThat(Cpf.of("529.982.247-25")).isEqualTo(Cpf.of("52998224725"));
    }
}
