package br.com.pixmanager.shared.logging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PiiMaskerTest {

    @Test
    void shouldMaskFormattedAndUnformattedCpf() {
        assertThat(PiiMasker.maskText("devedor 529.982.247-25 pagou")).isEqualTo("devedor ***.982.247-** pagou");
        assertThat(PiiMasker.maskText("cpf=52998224725;")).isEqualTo("cpf=***.982.247-**;");
    }

    @Test
    void shouldMaskNumericAndAlphanumericCnpj() {
        assertThat(PiiMasker.maskText("empresa 12.ABC.345/01DE-35")).isEqualTo("empresa **.ABC.345/01DE-**");
        assertThat(PiiMasker.maskText("11222333000181")).isEqualTo("**.222.333/0001-**");
    }

    @Test
    void shouldNotMaskNumbersThatAreNotValidDocuments() {
        // telefone, valor, timestamp e e2eId: nada disso é CPF/CNPJ válido
        String text = "tel 11987654321 valor 12345678901 ts 1760543210123 e2e E12345678202610151200abcdefghijk";
        assertThat(PiiMasker.maskText(text)).isEqualTo(text);
    }

    @Test
    void shouldBeNullSafe() {
        assertThat(PiiMasker.maskText(null)).isNull();
        assertThat(PiiMasker.maskText("")).isEmpty();
    }
}
