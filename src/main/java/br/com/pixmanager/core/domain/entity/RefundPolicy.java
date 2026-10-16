package br.com.pixmanager.core.domain.entity;

import br.com.pixmanager.core.domain.enums.RefundNature;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.BusinessException;

import java.time.Duration;
import java.time.Instant;

/** Regras de devolução sem I/O (BR-REF-03..06). Os prazos vêm de configuração, nunca de constante. */
public final class RefundPolicy {

    private RefundPolicy() {}

    /** BR-REF-05: devolução comum até {@code window} (padrão 90 dias) depois da liquidação do Pix. */
    public static void assertWithinWindow(Instant settledAt, Instant now, Duration window) {
        if (now.isAfter(settledAt.plus(window))) {
            throw new BusinessException(ErrorCodes.REFUND_OUT_OF_WINDOW, "Fora do prazo para devolução deste Pix.");
        }
    }

    /** BR-REF-06: natureza MED só pelo fluxo MED (esta porta aceita ORIGINAL/RETIRADA). */
    public static void assertNatureAllowedForClient(RefundNature nature) {
        if (nature != null && nature.isMed()) {
            throw new BusinessException(ErrorCodes.BUSINESS_RULE, "Natureza MED só é permitida pelo fluxo MED.");
        }
    }
}
