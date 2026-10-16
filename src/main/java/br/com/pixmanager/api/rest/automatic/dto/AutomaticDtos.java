package br.com.pixmanager.api.rest.automatic.dto;

import br.com.pixmanager.core.domain.enums.AutomaticPeriodicity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class AutomaticDtos {

    private static final String MONEY = "\\d{1,10}\\.\\d{2}";

    private AutomaticDtos() {}

    /** Solicitação do recebedor (no produto chega do spi-gateway). {@code idRec}: R + (R|N) + 27 alfanuméricos. */
    public record ReceiveAuthorization(@NotBlank @Pattern(regexp = "R[RN][a-zA-Z0-9]{27}") String idRec, @NotNull UUID payerAccountId,
                                       @NotBlank @Size(max = 18) String payerDocument, @NotBlank @Size(max = 18) String receiverDocument,
                                       @NotBlank @Size(max = 200) String receiverName, @NotBlank @Size(max = 35) String contract,
                                       @Size(max = 35) String objectDescription, @NotNull AutomaticPeriodicity periodicity, @NotNull LocalDate startDate,
                                       LocalDate endDate, @Pattern(regexp = MONEY) String fixedAmount, @Pattern(regexp = MONEY) String receiverMinAmount) {}

    public record Approve(@Pattern(regexp = MONEY) String payerMaxAmount) {}

    public record Reject(@NotBlank @Pattern(regexp = "AP1[345]") String rejectionCode) {}

    public record Cancel(@NotBlank @Pattern(regexp = "PAYER|RECEIVER|PSP") String cancelledBy) {}

    public record Limit(@NotNull @Pattern(regexp = MONEY) String payerMaxAmount) {}

    public record RegisterCharge(@NotBlank @Pattern(regexp = "[a-zA-Z0-9]{26,35}") String txid, @NotNull LocalDate dueDate,
                                 @NotNull @Pattern(regexp = MONEY) String amount, @NotBlank @Pattern(regexp = "\\d{8}") String receiverIspb,
                                 @NotBlank @Size(max = 4) String receiverBranch, @NotBlank @Size(max = 20) String receiverAccount,
                                 @NotBlank @Pattern(regexp = "CACC|SVGS|TRAN|SLRY") String receiverAccountType) {}

    public record AuthorizationResponse(UUID id, String idRec, boolean allowRetry, UUID payerAccountId, String receiverDocumentMasked, String receiverName,
                                        String contract, AutomaticPeriodicity periodicity, LocalDate startDate, LocalDate endDate, String fixedAmount,
                                        String receiverMinAmount, String payerMaxAmount, String status, String requestStatus, String rejectionCode,
                                        String cancelledBy, Instant approvedAt, Instant requestExpiresAt, long version) {}

    public record ChargeResponse(UUID id, UUID authorizationId, String txid, LocalDate dueDate, String amount, String status) {}

    public record AttemptResponse(UUID id, String type, String status, String endToEndId, LocalDate settlementDate, UUID paymentOrderId, String lastErrorCode) {}
}
