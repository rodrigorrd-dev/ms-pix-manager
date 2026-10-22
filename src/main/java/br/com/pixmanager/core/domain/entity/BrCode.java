package br.com.pixmanager.core.domain.entity;

import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.BusinessException;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * BR Code (EMV® QRCPS MPM) do Pix — o "copia e cola" do QR. Valida o CRC16 (CCITT-FALSE, polinômio 0x1021, início 0xFFFF) e extrai
 * chave/URL, valor, txid e nome. QR ESTÁTICO traz a {@code chave} (sub-tag 01 da tag 26); QR DINÂMICO traz a {@code url}
 * (sub-tag 25) do payload — buscar e validar o JWS desse payload é backlog (docs/10), então a decodificação aqui devolve a URL.
 */
public record BrCode(String payload, Optional<String> pixKey, Optional<String> paymentUrl, Optional<Money> amount, Optional<String> txid,
                     Optional<String> merchantName, Optional<String> merchantCity) {

    private static final String GUI = "br.gov.bcb.pix";

    public boolean isDynamic() {
        return paymentUrl.isPresent();
    }

    public static BrCode parse(String payload) {
        if (payload == null || payload.length() < 12 || !payload.startsWith("000201")) {
            throw invalid();
        }
        Map<String, String> root = tlv(payload);
        String checksum = root.get("63");
        if (checksum == null || checksum.length() != 4 || !payload.substring(payload.length() - 8, payload.length() - 4).equals("6304")
                || !crc16(payload.substring(0, payload.length() - 4)).equalsIgnoreCase(checksum)) {
            throw new BusinessException(ErrorCodes.INVALID_BR_CODE, "BR Code com CRC inválido.");
        }
        String account = root.get("26");
        if (account == null) {
            throw invalid();
        }
        Map<String, String> accountFields = tlv(account);
        if (accountFields.get("00") == null || !GUI.equalsIgnoreCase(accountFields.get("00"))) {
            throw new BusinessException(ErrorCodes.INVALID_BR_CODE, "BR Code não é do Pix (GUI br.gov.bcb.pix ausente).");
        }
        Optional<Money> amount = Optional.ofNullable(root.get("54")).map(Money::parseBcb);
        String additional = root.get("62");
        Optional<String> txid = additional == null ? Optional.empty() : Optional.ofNullable(tlv(additional).get("05")).filter(value -> !"***".equals(value));
        return new BrCode(payload, Optional.ofNullable(accountFields.get("01")), Optional.ofNullable(accountFields.get("25")), amount, txid,
                Optional.ofNullable(root.get("59")), Optional.ofNullable(root.get("60")));
    }

    /** CRC-16/CCITT-FALSE em hexadecimal maiúsculo de 4 dígitos. */
    public static String crc16(String data) {
        int checksum = 0xFFFF;
        for (byte byteValue : data.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            checksum ^= (byteValue & 0xFF) << 8;
            for (int index = 0; index < 8; index++) {
                checksum = (checksum & 0x8000) != 0 ? (checksum << 1) ^ 0x1021 : checksum << 1;
                checksum &= 0xFFFF;
            }
        }
        return String.format("%04X", checksum);
    }

    /** Lê TLV de 2+2+N caracteres; sequência malformada ⇒ BR Code inválido (nunca ArrayIndexOutOfBounds). */
    private static Map<String, String> tlv(String data) {
        Map<String, String> map = new HashMap<>();
        int index = 0;
        while (index < data.length()) {
            if (index + 4 > data.length()) {
                throw invalid();
            }
            String tag = data.substring(index, index + 2);
            int length;
            try {
                length = Integer.parseInt(data.substring(index + 2, index + 4));
            } catch (NumberFormatException exception) {
                throw invalid();
            }
            if (index + 4 + length > data.length()) {
                throw invalid();
            }
            map.putIfAbsent(tag, data.substring(index + 4, index + 4 + length));
            index += 4 + length;
        }
        return map;
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCodes.INVALID_BR_CODE, "BR Code inválido.");
    }
}
