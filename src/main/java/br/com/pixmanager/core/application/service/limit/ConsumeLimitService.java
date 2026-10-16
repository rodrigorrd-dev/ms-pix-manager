package br.com.pixmanager.core.application.service.limit;

import br.com.pixmanager.core.domain.entity.AccountInfo;
import br.com.pixmanager.core.domain.entity.AccountLimit;
import br.com.pixmanager.core.domain.entity.LimitDecision;
import br.com.pixmanager.core.domain.entity.LimitPolicy;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.enums.LimitScope;
import br.com.pixmanager.core.port.in.account.FindAccountUseCase;
import br.com.pixmanager.core.port.in.limit.ConsumeLimitUseCase;
import br.com.pixmanager.core.port.out.persistence.LimitPersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.LimitUsagePortOut;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.NotFoundException;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * BR-LIM-01..06: por transação (PF noturno / dispositivo não cadastrado / configurado) é checagem pura; o DIÁRIO é consumido
 * por {@code UPDATE} condicional atômico. Se o diário do dispositivo estoura, o diário geral já consumido é devolvido.
 */
public class ConsumeLimitService implements ConsumeLimitUseCase {

    private final LimitPersistencePortOut limits;
    private final LimitUsagePortOut usage;
    private final FindAccountUseCase accounts;
    private final LimitPolicy.Config config;

    public ConsumeLimitService(LimitPersistencePortOut limits, LimitUsagePortOut usage, FindAccountUseCase accounts,
                               LimitPolicy.Config config) {
        this.limits = limits;
        this.usage = usage;
        this.accounts = accounts;
        this.config = config;
    }

    @Override
    public LimitDecision consume(UUID accountId, Money amount, Instant at, boolean deviceRegistered) {
        AccountInfo account = accounts.findById(accountId)
                .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND, "Conta não encontrada."));
        Map<LimitScope, Money> configured = effective(accountId, at);

        if (LimitPolicy.checkPerTransaction(account.holderType(), amount, at, deviceRegistered, configured, config)
                instanceof LimitPolicy.PerTransactionDecision.Rejected rejected) {
            return new LimitDecision.Rejected(rejected.scope(), rejected.limit());
        }
        LimitPolicy.DailyChecks checks = LimitPolicy.dailyChecks(account.holderType(), at, deviceRegistered, configured, config);
        if (!usage.tryConsume(accountId, checks.day(), LimitScope.DAILY, amount, checks.dailyLimit())) {
            return new LimitDecision.Rejected(LimitScope.DAILY, checks.dailyLimit());
        }
        if (checks.deviceDailyLimit().isPresent()) {
            Money deviceMax = checks.deviceDailyLimit().get();
            if (!usage.tryConsume(accountId, checks.day(), LimitScope.DEVICE_UNREGISTERED_DAILY, amount, deviceMax)) {
                usage.release(accountId, checks.day(), LimitScope.DAILY, amount);       // compensa o diário já consumido
                return new LimitDecision.Rejected(LimitScope.DEVICE_UNREGISTERED_DAILY, deviceMax);
            }
        }
        return new LimitDecision.Allowed(checks.day(), checks.deviceDailyLimit().isPresent(), amount);
    }

    @Override
    public void release(UUID accountId, LimitDecision.Allowed allowed) {
        usage.release(accountId, allowed.day(), LimitScope.DAILY, allowed.amount());
        if (allowed.deviceDailyConsumed()) {
            usage.release(accountId, allowed.day(), LimitScope.DEVICE_UNREGISTERED_DAILY, allowed.amount());
        }
    }

    private Map<LimitScope, Money> effective(UUID accountId, Instant at) {
        Map<LimitScope, Money> map = new EnumMap<>(LimitScope.class);
        for (AccountLimit limit : limits.findByAccount(accountId)) {
            map.put(limit.scope(), limit.effective(at));
        }
        return map;
    }
}
