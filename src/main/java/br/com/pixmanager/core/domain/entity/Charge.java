package br.com.pixmanager.core.domain.entity;

import br.com.pixmanager.core.domain.enums.ChargeStatus;
import br.com.pixmanager.core.domain.enums.ChargeType;

import br.com.pixmanager.shared.exception.BadRequestException;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.InvalidTransitionException;
import br.com.pixmanager.core.domain.entity.Money;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Agregado da cobrança ({@code cob}/{@code cobv}). Imutável: cada operação devolve nova instância, e TODA mudança de
 * status passa por {@link #moveTo}, que valida {@link ChargeStatus#canTransitionTo}. Sem framework no domínio.
 */
public record Charge(UUID id, ChargeType type, TxId txid, UUID receiverAccountId, String receiverDocumentHash, UUID pixKeyId,
                     ChargeStatus status, Money originalAmount, Integer expirationSeconds, Instant expiresAt,
                     LocalDate dueDate, Integer validityAfterDueDays, Debtor debtor, String payerRequest,
                     List<AdditionalInfo> additionalInfo, PixLocation location, int revision, UUID paidReceivedId,
                     Instant removedAt, long version, Instant createdAt) {

    public static final int MAX_PAYER_REQUEST = 140;
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Sao_Paulo");

    public Charge {
        additionalInfo = additionalInfo == null ? List.of() : List.copyOf(additionalInfo);
        if (!originalAmount.isPositive() || originalAmount.exceedsBcbMaximum()) {
            throw new BadRequestException(ErrorCodes.INVALID_PAYLOAD, "valor.original deve ser maior que zero (até 9999999999.99).");
        }
        if (payerRequest != null && payerRequest.length() > MAX_PAYER_REQUEST) {
            throw new BadRequestException(ErrorCodes.INVALID_PAYLOAD, "solicitacaoPagador aceita até 140 caracteres.");
        }
    }

    public static Charge createImmediate(UUID id, TxId txid, UUID receiverAccountId, String receiverDocumentHash, UUID pixKeyId,
                                         Money amount, int expirationSeconds, Debtor debtor, String payerRequest,
                                         List<AdditionalInfo> info, PixLocation location, Instant now) {
        if (expirationSeconds < 1) {
            throw new BadRequestException(ErrorCodes.INVALID_PAYLOAD, "calendario.expiracao deve ser >= 1 segundo.");
        }
        return new Charge(id, ChargeType.IMMEDIATE, txid, receiverAccountId, receiverDocumentHash, pixKeyId,
                ChargeStatus.ATIVA, amount, expirationSeconds, now.plusSeconds(expirationSeconds), null, null, debtor,
                payerRequest, AdditionalInfo.validated(info), location, 0, null, null, 0, now);
    }

    /** {@code cobv}: o BCB exige devedor identificado. */
    public static Charge createDue(UUID id, TxId txid, UUID receiverAccountId, String receiverDocumentHash, UUID pixKeyId,
                                   Money amount, LocalDate dueDate, int validityAfterDueDays, Debtor debtor,
                                   String payerRequest, List<AdditionalInfo> info, PixLocation location, Instant now) {
        if (debtor == null) {
            throw new BadRequestException(ErrorCodes.INVALID_PAYLOAD, "A cobrança com vencimento exige devedor (cpf/cnpj e nome).");
        }
        if (validityAfterDueDays < 0) {
            throw new BadRequestException(ErrorCodes.INVALID_PAYLOAD, "validadeAposVencimento deve ser >= 0.");
        }
        if (dueDate == null || dueDate.isBefore(LocalDate.ofInstant(now, BUSINESS_ZONE))) {
            throw new BadRequestException(ErrorCodes.INVALID_PAYLOAD, "dataDeVencimento não pode estar no passado.");
        }
        return new Charge(id, ChargeType.DUE, txid, receiverAccountId, receiverDocumentHash, pixKeyId, ChargeStatus.ATIVA,
                amount, null, null, dueDate, validityAfterDueDays, debtor, payerRequest, AdditionalInfo.validated(info),
                location, 0, null, null, 0, now);
    }

    /** BR-COB-03/09: imediata expira em {@code expiresAt}; com vencimento, até {@code dueDate + validade} (inclusive). */
    public boolean isExpiredAt(Instant now) {
        return switch (type) {
            case IMMEDIATE -> now.isAfter(expiresAt);
            case DUE -> LocalDate.ofInstant(now, BUSINESS_ZONE).isAfter(dueDate.plusDays(validityAfterDueDays));
        };
    }

    public boolean isActive() {
        return status == ChargeStatus.ATIVA;
    }

    public Charge withLocation(PixLocation newLocation) {
        return new Charge(id, type, txid, receiverAccountId, receiverDocumentHash, pixKeyId, status, originalAmount,
                expirationSeconds, expiresAt, dueDate, validityAfterDueDays, debtor, payerRequest, additionalInfo,
                newLocation, revision, paidReceivedId, removedAt, version, createdAt);
    }

    /** Pagamento conciliado: ATIVA → CONCLUIDA, vinculando o Pix. */
    public Charge markPaid(UUID receivedPixId) {
        return moveTo(ChargeStatus.CONCLUIDA, receivedPixId, null);
    }

    public Charge removedByReceiver(Instant at) {
        return moveTo(ChargeStatus.REMOVIDA_PELO_USUARIO_RECEBEDOR, null, at);
    }

    public Charge removedByPsp(Instant at) {
        return moveTo(ChargeStatus.REMOVIDA_PELO_PSP, null, at);
    }

    /** BR-COB-08: revisão só em ATIVA; incrementa {@code revisao}. */
    public Charge revised(Money newAmount, Debtor newDebtor, String newPayerRequest, Integer newExpirationSeconds, Instant now) {
        if (!isActive()) {
            throw new InvalidTransitionException(ErrorCodes.CHARGE_NOT_ACTIVE, "Só cobrança ATIVA pode ser revisada.");
        }
        Instant newExpiresAt = type == ChargeType.IMMEDIATE && newExpirationSeconds != null
                ? createdAt.plusSeconds(newExpirationSeconds) : expiresAt;
        Integer newExp = type == ChargeType.IMMEDIATE && newExpirationSeconds != null ? newExpirationSeconds : expirationSeconds;
        return new Charge(id, type, txid, receiverAccountId, receiverDocumentHash, pixKeyId, status,
                newAmount != null ? newAmount : originalAmount, newExp, newExpiresAt, dueDate, validityAfterDueDays,
                newDebtor != null ? newDebtor : debtor, newPayerRequest != null ? newPayerRequest : payerRequest,
                additionalInfo, location, revision + 1, paidReceivedId, removedAt, version, createdAt);
    }

    /** Mesmo pedido de criação? Base da idempotência do {@code PUT /cob/{txid}} (corpo igual ⇒ mesmo recurso). */
    public boolean sameRequestAs(Charge other) {
        return type == other.type && pixKeyId.equals(other.pixKeyId) && originalAmount.equals(other.originalAmount)
                && Objects.equals(expirationSeconds, other.expirationSeconds) && Objects.equals(dueDate, other.dueDate)
                && Objects.equals(validityAfterDueDays, other.validityAfterDueDays)
                && Objects.equals(debtor == null ? null : debtor.document(), other.debtor == null ? null : other.debtor.document())
                && Objects.equals(debtor == null ? null : debtor.name(), other.debtor == null ? null : other.debtor.name())
                && Objects.equals(payerRequest, other.payerRequest) && additionalInfo.equals(other.additionalInfo);
    }

    private Charge moveTo(ChargeStatus target, UUID paidReceivedId, Instant removedAt) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidTransitionException(ErrorCodes.CHARGE_NOT_ACTIVE,
                    "Transição de cobrança inválida: " + status + " -> " + target);
        }
        return new Charge(id, type, txid, receiverAccountId, receiverDocumentHash, pixKeyId, target, originalAmount,
                expirationSeconds, expiresAt, dueDate, validityAfterDueDays, debtor, payerRequest, additionalInfo, location,
                revision, paidReceivedId != null ? paidReceivedId : this.paidReceivedId, removedAt, version, createdAt);
    }
}
