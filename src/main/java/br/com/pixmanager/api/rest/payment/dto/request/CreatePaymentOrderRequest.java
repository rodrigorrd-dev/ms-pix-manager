package br.com.pixmanager.api.rest.payment.dto.request;

import br.com.pixmanager.core.domain.enums.PaymentMethod;
import br.com.pixmanager.core.domain.enums.PixKeyType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * {@code method}: KEY (chave + tipo), MANUAL (dados bancários), QR_STATIC ({@code qrPayload}). {@code amount} é string decimal
 * ({@code ddd.dd}); {@code deviceId} ausente ⇒ dispositivo tratado como NÃO cadastrado (limites menores).
 */
public record CreatePaymentOrderRequest(
        @NotNull UUID payerAccountId,
        @NotNull PaymentMethod method,
        @Pattern(regexp = "\\d{1,10}\\.\\d{2}") String amount,
        @Valid Destination destination,
        @Size(max = 4096) String qrPayload,
        @Size(max = 140) String payerMessage,
        @Size(max = 128) String deviceId) {

    public record Destination(
            PixKeyType pixKeyType,
            @Size(max = 77) String pixKey,
            @Size(max = 18) String documentHint,
            @Pattern(regexp = "\\d{8}") String ispb,
            @Size(max = 4) String branch,
            @Size(max = 20) String account,
            @Pattern(regexp = "CACC|SVGS|TRAN|SLRY") String accountType,
            @Size(max = 200) String name,
            @Size(max = 18) String document) {}
}
