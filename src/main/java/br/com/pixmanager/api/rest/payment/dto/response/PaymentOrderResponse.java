package br.com.pixmanager.api.rest.payment.dto.response;

import br.com.pixmanager.core.domain.enums.PaymentMethod;
import br.com.pixmanager.core.domain.enums.PaymentOrigin;
import br.com.pixmanager.core.domain.enums.PaymentStatus;

import java.time.Instant;
import java.util.UUID;

/** O documento do favorecido sai MASCARADO. {@code endToEndId} é a chave de idempotência do core e o id do SPI. */
public record PaymentOrderResponse(UUID id, UUID payerAccountId, PaymentMethod method, PaymentOrigin origin, PaymentStatus status,
                                   String amount, String endToEndId, String rejectReasonCode, String destinationName,
                                   String destinationDocumentMasked, Instant requestedAt, Instant settledAt) {}
