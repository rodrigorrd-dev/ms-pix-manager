package br.com.pixmanager.support;

import br.com.pixmanager.core.domain.entity.BrCode;

/** Monta um BR Code (EMV) válido com CRC16 correto para os testes. */
public final class BrCodeFactory {

    private BrCodeFactory() {}

    public static String staticQr(String pixKey, String amount, String name, String city, String txid) {
        String account = tlv("00", "br.gov.bcb.pix") + tlv("01", pixKey);
        StringBuilder sb = new StringBuilder();
        sb.append(tlv("00", "01")).append(tlv("01", "12")).append(tlv("26", account)).append(tlv("52", "0000")).append(tlv("53", "986"));
        if (amount != null) {
            sb.append(tlv("54", amount));
        }
        sb.append(tlv("58", "BR")).append(tlv("59", name)).append(tlv("60", city)).append(tlv("62", tlv("05", txid == null ? "***" : txid)));
        sb.append("6304");
        return sb + BrCode.crc16(sb.toString());
    }

    public static String dynamicQr(String url) {
        String account = tlv("00", "br.gov.bcb.pix") + tlv("25", url);
        StringBuilder sb = new StringBuilder();
        sb.append(tlv("00", "01")).append(tlv("01", "12")).append(tlv("26", account)).append(tlv("52", "0000")).append(tlv("53", "986"))
                .append(tlv("58", "BR")).append(tlv("59", "Loja")).append(tlv("60", "SAO PAULO")).append(tlv("62", tlv("05", "***")));
        sb.append("6304");
        return sb + BrCode.crc16(sb.toString());
    }

    public static String tlv(String tag, String value) {
        return tag + String.format("%02d", value.length()) + value;
    }
}
