package br.com.pixmanager.core.domain.entity;

import br.com.pixmanager.core.domain.enums.LimitScope;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.BusinessException;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Limite configurado de uma conta/escopo. BR-LIM-03: <b>aumento</b> só vale depois da carência (24–48 h) e <b>redução é
 * imediata</b>; BR-LIM-04: o cliente altera até o {@code ceiling} (teto do banco). O {@code pending} é aplicado
 * automaticamente quando {@code pendingEffectiveAt <= now} (sem job: a leitura "enxerga" o vigente).
 */
public record AccountLimit(UUID id, UUID accountId, LimitScope scope, Money amount, Money ceiling, Money pendingAmount,
                           Instant pendingRequestedAt, Instant pendingEffectiveAt, long version) {

    public AccountLimit {
        if (amount.isNegative()) {
            throw new IllegalArgumentException("limite não pode ser negativo");
        }
        if (ceiling != null && amount.isGreaterThan(ceiling)) {
            throw new IllegalArgumentException("limite acima do teto do banco");
        }
        if ((pendingAmount == null) != (pendingEffectiveAt == null)) {
            throw new IllegalArgumentException("aumento pendente exige valor e data de vigência");
        }
    }

    public static AccountLimit initial(UUID id, UUID accountId, LimitScope scope, Money amount, Money ceiling) {
        return new AccountLimit(id, accountId, scope, amount, ceiling, null, null, null, 0);
    }

    /** Valor VIGENTE em {@code now}: o pendente já liberado conta; o que ainda espera a carência não. */
    public Money effective(Instant now) {
        return pendingAmount != null && !pendingEffectiveAt.isAfter(now) ? pendingAmount : amount;
    }

    /** {@code delay}: carência do aumento (padrão 24 h; o BCB admite entre 24 h e 48 h). */
    public AccountLimit requestChange(Money requested, Instant now, Duration delay) {
        if (requested.isNegative()) {
            throw new BusinessException(ErrorCodes.INVALID_PAYLOAD, "O limite não pode ser negativo.");
        }
        if (ceiling != null && requested.isGreaterThan(ceiling)) {
            throw new BusinessException(ErrorCodes.LIMIT_ABOVE_CEILING, "O limite solicitado excede o teto definido pelo banco.");
        }
        Money current = effective(now);
        AccountLimit settled = applyDuePending(now);
        if (!requested.isGreaterThan(current)) {
            return new AccountLimit(id, accountId, scope, requested, ceiling, null, null, null, version);   // redução: imediata (e cancela pendente)
        }
        return new AccountLimit(id, accountId, scope, settled.amount, ceiling, requested, now, now.plus(delay), version);
    }

    private AccountLimit applyDuePending(Instant now) {
        return pendingAmount != null && !pendingEffectiveAt.isAfter(now)
                ? new AccountLimit(id, accountId, scope, pendingAmount, ceiling, null, null, null, version) : this;
    }
}
