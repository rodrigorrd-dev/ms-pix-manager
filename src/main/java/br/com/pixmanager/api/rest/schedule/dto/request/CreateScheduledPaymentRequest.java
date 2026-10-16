package br.com.pixmanager.api.rest.schedule.dto.request;

import br.com.pixmanager.api.rest.payment.dto.request.CreatePaymentOrderRequest.Destination;
import br.com.pixmanager.core.domain.enums.PaymentMethod;
import br.com.pixmanager.core.domain.enums.Recurrence;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code method}: KEY ou MANUAL (QR não se agenda). {@code firstDate}: dia em America/Sao_Paulo, posterior a hoje. Único ({@code ONCE}):
 * sem {@code endDate}/{@code occurrencesTotal}. Recorrente: exatamente um dos dois.
 */
public record CreateScheduledPaymentRequest(
        @NotNull UUID payerAccountId,
        @NotNull Recurrence recurrence,
        @NotNull PaymentMethod method,
        @Valid Destination destination,
        @NotNull @Pattern(regexp = "\\d{1,10}\\.\\d{2}") String amount,
        @Size(max = 140) String payerMessage,
        @NotNull LocalDate firstDate,
        LocalDate endDate,
        @Min(2) @Max(120) Integer occurrencesTotal) {}
