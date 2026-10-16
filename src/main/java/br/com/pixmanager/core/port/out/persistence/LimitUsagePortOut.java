package br.com.pixmanager.core.port.out.persistence;

import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.enums.LimitScope;

import java.time.LocalDate;
import java.util.UUID;

/** Consumo de limite por dia. {@code tryConsume} é UM {@code UPDATE ... WHERE usado + valor <= limite}: sem read→decide→write. */
public interface LimitUsagePortOut {

    boolean tryConsume(UUID accountId, LocalDate day, LimitScope scope, Money amount, Money max);

    void release(UUID accountId, LocalDate day, LimitScope scope, Money amount);
}
