package br.com.pixmanager.api.rest.schedule.dto.response;

import br.com.pixmanager.core.domain.enums.PaymentMethod;
import br.com.pixmanager.core.domain.enums.PixKeyType;
import br.com.pixmanager.core.domain.enums.Recurrence;
import br.com.pixmanager.core.domain.enums.ScheduleStatus;

import java.time.LocalDate;
import java.util.UUID;

/** Destino sempre MASCARADO (chave e documento): a resposta não devolve PII. */
public record ScheduledPaymentResponse(UUID id, UUID payerAccountId, Recurrence recurrence, PaymentMethod method, String amount,
                                       LocalDate firstDate, LocalDate endDate, Integer occurrencesTotal, ScheduleStatus status,
                                       Destination destination, String payerMessage, long version) {

    public record Destination(PixKeyType pixKeyType, String pixKeyMasked, String name, String documentMasked, String ispb) {}
}
