package br.com.pixmanager.core.domain.entity;

import br.com.pixmanager.core.domain.enums.RefundNature;
import br.com.pixmanager.core.domain.enums.RefundStatus;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.BadRequestException;
import br.com.pixmanager.shared.exception.InvalidTransitionException;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Devolução de Pix recebido. {@code clientRefundId} ({@code [a-zA-Z0-9]{1,35}}) é a chave de idempotência por
 * {@code (endToEndId, id)} (BR-REF-01); {@code rtrId} (32) é gerado na criação e é a chave de idempotência do core
 * (reservar → confirmar|cancelar). Imutável: transições devolvem nova instância.
 */
public record Refund(UUID id, String clientRefundId, UUID receivedId, EndToEndId endToEndId, String rtrId, Money amount,
                     RefundNature nature, String description, RefundStatus status, String statusReason, Instant requestedAt,
                     Instant settledAt, long version) {

    private static final Pattern CLIENT_ID = Pattern.compile("[a-zA-Z0-9]{1,35}");

    public Refund {
        if (clientRefundId == null || !CLIENT_ID.matcher(clientRefundId).matches()) {
            throw new BadRequestException(ErrorCodes.INVALID_PAYLOAD, "id da devolução inválido: esperado [a-zA-Z0-9]{1,35}.");
        }
        if (!amount.isPositive()) {
            throw new BadRequestException(ErrorCodes.INVALID_PAYLOAD, "O valor da devolução deve ser maior que zero.");
        }
        if (description != null && description.length() > 140) {
            throw new BadRequestException(ErrorCodes.INVALID_PAYLOAD, "descricao aceita até 140 caracteres.");
        }
    }

    public static Refund request(UUID id, String clientRefundId, UUID receivedId, EndToEndId endToEndId, String rtrId, Money amount,
                                 RefundNature nature, String description, Instant now) {
        return new Refund(id, clientRefundId, receivedId, endToEndId, rtrId, amount, nature == null ? RefundNature.ORIGINAL : nature,
                description, RefundStatus.EM_PROCESSAMENTO, null, now, null, 0);
    }

    public Refund settled(Instant at) {
        return moveTo(RefundStatus.DEVOLVIDO, null, at);
    }

    public Refund failed(String reason) {
        return moveTo(RefundStatus.NAO_REALIZADO, reason == null ? null : reason.substring(0, Math.min(140, reason.length())), null);
    }

    /** Mesmo pedido? Repetição idêntica devolve o mesmo recurso; corpo diferente ⇒ 422 (BR-REF-01). */
    public boolean sameRequestAs(Money otherAmount, RefundNature otherNature, String otherDescription) {
        return amount.equals(otherAmount) && nature == (otherNature == null ? RefundNature.ORIGINAL : otherNature)
                && java.util.Objects.equals(description, otherDescription);
    }

    private Refund moveTo(RefundStatus target, String reason, Instant settledAt) {
        if (status == target) {
            return this;                       // idempotente
        }
        if (!status.canTransitionTo(target)) {
            throw new InvalidTransitionException(ErrorCodes.BUSINESS_RULE, "Transição de devolução inválida: " + status + " -> " + target);
        }
        return new Refund(id, clientRefundId, receivedId, endToEndId, rtrId, amount, nature, description, target, reason,
                requestedAt, settledAt, version);
    }
}
