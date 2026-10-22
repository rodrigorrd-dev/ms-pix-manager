package br.com.pixmanager.core.domain.entity;

import br.com.pixmanager.core.domain.enums.PaymentMethod;
import br.com.pixmanager.core.domain.enums.PaymentOrigin;
import br.com.pixmanager.core.domain.enums.PaymentStatus;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.BadRequestException;
import br.com.pixmanager.shared.exception.InvalidTransitionException;

import java.time.Instant;
import java.util.UUID;

/**
 * Ordem de pagamento Pix. O {@code endToEndId} nasce NA CRIAÇÃO e é a chave de idempotência do core (reservar → confirmar |
 * cancelar). Máquina de estados em {@link PaymentStatus}; toda transição passa por {@link #moveTo}.
 */
public record PaymentOrder(UUID id, UUID payerAccountId, PaymentMethod method, PaymentOrigin origin, PaymentDestination destination,
                           Money amount, String qrTxid, String payerMessage, EndToEndId endToEndId, PaymentStatus status,
                           String rejectReasonCode, String rejectReasonDetail, UUID ledgerReservationId, boolean deviceRegistered,
                           Instant requestedAt, Instant sentAt, Instant settledAt, long version) {

    public PaymentOrder {
        if (!amount.isPositive() || amount.exceedsBcbMaximum()) {
            throw new BadRequestException(ErrorCodes.INVALID_PAYLOAD, "O valor do pagamento deve ser maior que zero (até 9999999999.99).");
        }
        if (payerMessage != null && payerMessage.length() > 140) {
            throw new BadRequestException(ErrorCodes.INVALID_PAYLOAD, "A mensagem ao favorecido aceita até 140 caracteres.");
        }
    }

    public static PaymentOrder create(UUID id, UUID payerAccountId, PaymentMethod method, PaymentOrigin origin,
                                      PaymentDestination destination, Money amount, String qrTxid, String payerMessage,
                                      EndToEndId e2e, boolean deviceRegistered, Instant now) {
        return new PaymentOrder(id, payerAccountId, method, origin, destination, amount, qrTxid, payerMessage, e2e,
                PaymentStatus.CREATED, null, null, null, deviceRegistered, now, null, null, 0);
    }

    public PaymentOrder validated(UUID reservationId) {
        return withReservation(moveTo(PaymentStatus.VALIDATED, null, null, null, null, null), reservationId);
    }

    public PaymentOrder reserved() {
        return moveTo(PaymentStatus.RESERVED, null, null, null, null, null);
    }

    public PaymentOrder sentToSpi(Instant at) {
        return moveTo(PaymentStatus.SENT_TO_SPI, null, null, at, null, null);
    }

    public PaymentOrder settled(Instant at) {
        return moveTo(PaymentStatus.SETTLED, null, null, null, at, null);
    }

    /** {@code code}: ISO 20022 do SPI (AC03, AM04, AB03…) ou código do PSP (limite, saldo). */
    public PaymentOrder rejected(String code, String detail) {
        return moveTo(PaymentStatus.REJECTED, code, detail, null, null, null);
    }

    public PaymentOrder failedTechnical(String detail) {
        return moveTo(PaymentStatus.FAILED_TECHNICAL, null, detail, null, null, null);
    }

    public PaymentOrder cancelled() {
        if (!status.isCancellable()) {
            throw new InvalidTransitionException(ErrorCodes.BUSINESS_RULE, "A ordem já foi enviada ao SPI e não pode ser cancelada.");
        }
        return moveTo(PaymentStatus.CANCELLED, null, null, null, null, null);
    }

    private PaymentOrder withReservation(PaymentOrder paymentOrder, UUID reservationId) {
        return new PaymentOrder(paymentOrder.id, paymentOrder.payerAccountId, paymentOrder.method, paymentOrder.origin, paymentOrder.destination, paymentOrder.amount, paymentOrder.qrTxid, paymentOrder.payerMessage,
                paymentOrder.endToEndId, paymentOrder.status, paymentOrder.rejectReasonCode, paymentOrder.rejectReasonDetail, reservationId, paymentOrder.deviceRegistered, paymentOrder.requestedAt,
                paymentOrder.sentAt, paymentOrder.settledAt, paymentOrder.version);
    }

    private PaymentOrder moveTo(PaymentStatus target, String code, String detail, Instant sent, Instant settled, UUID unused) {
        if (status == target) {
            return this;                              // idempotente
        }
        if (!status.canTransitionTo(target)) {
            throw new InvalidTransitionException(ErrorCodes.BUSINESS_RULE, "Transição de ordem inválida: " + status + " -> " + target);
        }
        return new PaymentOrder(id, payerAccountId, method, origin, destination, amount, qrTxid, payerMessage, endToEndId, target,
                code != null ? code : rejectReasonCode, detail != null ? detail : rejectReasonDetail, ledgerReservationId,
                deviceRegistered, requestedAt, sent != null ? sent : sentAt, settled != null ? settled : settledAt, version);
    }
}
