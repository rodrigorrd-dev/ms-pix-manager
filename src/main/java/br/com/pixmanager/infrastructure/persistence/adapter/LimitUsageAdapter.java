package br.com.pixmanager.infrastructure.persistence.adapter;

import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.enums.LimitScope;
import br.com.pixmanager.core.port.out.persistence.LimitUsagePortOut;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Consumo ATÔMICO: o {@code UPDATE ... WHERE used_amount + :v <= :max} é a decisão (uma instrução, uma linha travada) — duas
 * requisições simultâneas nunca ultrapassam o limite (concurrency-review: nada de read→decide→write).
 */
@Component
public class LimitUsageAdapter implements LimitUsagePortOut {

    private final JdbcClient jdbc;

    public LimitUsageAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public boolean tryConsume(UUID accountId, LocalDate day, LimitScope scope, Money amount, Money max) {
        jdbc.sql("""
                INSERT INTO limit_usage (account_id, usage_date, scope, used_amount) VALUES (:a, :d, :s, 0)
                ON CONFLICT (account_id, usage_date, scope) DO NOTHING
                """).param("a", accountId).param("d", day).param("s", scope.name()).update();
        return jdbc.sql("""
                UPDATE limit_usage SET used_amount = used_amount + :v, version = version + 1
                 WHERE account_id = :a AND usage_date = :d AND scope = :s AND used_amount + :v <= :max
                """).param("v", amount.value()).param("a", accountId).param("d", day).param("s", scope.name())
                .param("max", max.value()).update() == 1;
    }

    @Override
    @Transactional
    public void release(UUID accountId, LocalDate day, LimitScope scope, Money amount) {
        jdbc.sql("""
                UPDATE limit_usage SET used_amount = GREATEST(used_amount - :v, 0), version = version + 1
                 WHERE account_id = :a AND usage_date = :d AND scope = :s
                """).param("v", amount.value()).param("a", accountId).param("d", day).param("s", scope.name()).update();
    }
}
