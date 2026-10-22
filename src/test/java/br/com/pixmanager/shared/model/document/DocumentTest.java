package br.com.pixmanager.shared.model.document;

import br.com.pixmanager.shared.exception.InvalidDocumentException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentTest {

    @Test
    void shouldResolveCpfAndCnpj() {
        assertThat(Document.of("529.982.247-25")).isInstanceOf(Cpf.class);
        assertThat(Document.of("12.ABC.345/01DE-35")).isInstanceOf(Cnpj.class);
        assertThat(Document.of("52998224725").isIndividual()).isTrue();
        assertThat(Document.of("11222333000181").isIndividual()).isFalse();
    }

    @Test
    void shouldReturnEmptyForInvalid() {
        assertThat(Document.tryParse("123")).isEmpty();
        assertThatThrownBy(() -> Document.of("123")).isInstanceOf(InvalidDocumentException.class);
    }

    @Test
    void sealedSwitchShouldBeExhaustive() {
        Document document = Document.of("52998224725");
        String type = switch (document) {
            case Cpf cpf -> "pf:" + cpf.masked();
            case Cnpj cnpj -> "pj:" + cnpj.masked();
        };
        assertThat(type).isEqualTo("pf:***.982.247-**");
    }
}
