package br.com.pixmanager.core.domain.entity;

import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.support.BrCodeFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BrCode — EMV do Pix: CRC16/CCITT-FALSE, chave, valor, txid")
class BrCodeTest {

    @Test
    @DisplayName("CRC16/CCITT-FALSE: vetor de teste padrão \"123456789\" = 29B1")
    void crcMatchesTheStandardCheckValue() {
        assertThat(BrCode.crc16("123456789")).isEqualTo("29B1");
    }

    @Test
    void shouldParseStaticQr() {
        String payload = BrCodeFactory.staticQr("123e4567-e12b-12d1-a456-426655440000", "25.50", "Fulano de Tal", "BRASILIA", "PED0000000000000000000000001");
        BrCode qr = BrCode.parse(payload);
        assertThat(qr.isDynamic()).isFalse();
        assertThat(qr.pixKey()).contains("123e4567-e12b-12d1-a456-426655440000");
        assertThat(qr.amount()).contains(Money.of("25.50"));
        assertThat(qr.txid()).contains("PED0000000000000000000000001");
        assertThat(qr.merchantName()).contains("Fulano de Tal");
        assertThat(qr.merchantCity()).contains("BRASILIA");
    }

    @Test
    void shouldParseStaticQrWithoutAmountAndWithPlaceholderTxid() {
        BrCode qr = BrCode.parse(BrCodeFactory.staticQr("+5511999990001", null, "Loja", "SAO PAULO", null));
        assertThat(qr.amount()).isEmpty();
        assertThat(qr.txid()).as("*** = sem txid").isEmpty();
    }

    @Test
    void shouldRecognizeDynamicQrByTheUrl() {
        BrCode qr = BrCode.parse(BrCodeFactory.dynamicQr("pix.example.com/qr/v2/abc123"));
        assertThat(qr.isDynamic()).isTrue();
        assertThat(qr.paymentUrl()).contains("pix.example.com/qr/v2/abc123");
        assertThat(qr.pixKey()).isEmpty();
    }

    @Test
    void anySingleCharacterChangeBreaksTheCrc() {
        String ok = BrCodeFactory.staticQr("123e4567-e12b-12d1-a456-426655440000", "25.50", "Fulano", "BRASILIA", null);
        String tampered = ok.replace("25.50", "95.50");
        assertThatThrownBy(() -> BrCode.parse(tampered)).isInstanceOf(BusinessException.class).hasMessageContaining("CRC");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "00020126", "0002010102126304ABCD", "000201630", "00020126580014br.gov.bcb.pix0136"})
    void malformedPayloadsAreBusinessErrorsNeverCrashes(String payload) {
        assertThatThrownBy(() -> BrCode.parse(payload)).isInstanceOf(BusinessException.class);
    }

    @Test
    void nonPixQrIsRejected() {
        String account = BrCodeFactory.tlv("00", "com.outro") + BrCodeFactory.tlv("01", "x");
        String body = BrCodeFactory.tlv("00", "01") + BrCodeFactory.tlv("26", account) + "6304";
        assertThatThrownBy(() -> BrCode.parse(body + BrCode.crc16(body))).isInstanceOf(BusinessException.class).hasMessageContaining("Pix");
    }

    @Test
    void nullPayload() {
        assertThatThrownBy(() -> BrCode.parse(null)).isInstanceOf(BusinessException.class);
    }
}
