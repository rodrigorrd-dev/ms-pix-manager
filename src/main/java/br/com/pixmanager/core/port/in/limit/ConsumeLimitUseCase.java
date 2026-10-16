package br.com.pixmanager.core.port.in.limit;

import br.com.pixmanager.core.domain.entity.LimitDecision;
import br.com.pixmanager.core.domain.entity.Money;

import java.time.Instant;
import java.util.UUID;

/** Consome o limite de forma ATÔMICA; quem falhar depois chama {@link #release} (compensação). */
public interface ConsumeLimitUseCase {

    LimitDecision consume(UUID accountId, Money amount, Instant at, boolean deviceRegistered);

    void release(UUID accountId, LimitDecision.Allowed allowed);
}
