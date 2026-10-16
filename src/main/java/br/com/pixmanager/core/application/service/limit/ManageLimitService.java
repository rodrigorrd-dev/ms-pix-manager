package br.com.pixmanager.core.application.service.limit;

import br.com.pixmanager.core.domain.entity.AccountInfo;
import br.com.pixmanager.core.domain.entity.AccountLimit;
import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.LimitPolicy;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.enums.LimitScope;
import br.com.pixmanager.core.port.in.account.FindAccountUseCase;
import br.com.pixmanager.core.port.in.limit.ManageLimitUseCase;
import br.com.pixmanager.core.port.out.generator.IdGeneratorPortOut;
import br.com.pixmanager.core.port.out.persistence.LimitPersistencePortOut;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.NotFoundException;
import br.com.pixmanager.shared.exception.PreconditionFailedException;
import br.com.pixmanager.shared.exception.PreconditionRequiredException;
import br.com.pixmanager.shared.model.ExpectedVersion;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class ManageLimitService implements ManageLimitUseCase {

    /** Teto do banco por tipo de titular e atraso mínimo para aumento de limite (de {@code pix.policy.limits.*}, montado em UseCaseConfig). */
    public record Ceilings(Money individual, Money business, Duration increaseDelayMin) {}

    private final LimitPersistencePortOut persistence;
    private final FindAccountUseCase accounts;
    private final IdGeneratorPortOut ids;
    private final Clock clock;
    private final LimitPolicy.Config defaults;
    private final Ceilings ceilings;

    public ManageLimitService(LimitPersistencePortOut persistence, FindAccountUseCase accounts, IdGeneratorPortOut ids, Clock clock,
                              LimitPolicy.Config defaults, Ceilings ceilings) {
        this.persistence = persistence;
        this.accounts = accounts;
        this.ids = ids;
        this.clock = clock;
        this.defaults = defaults;
        this.ceilings = ceilings;
    }

    @Override
    public List<LimitView> list(Caller caller, UUID accountId) {
        AccountInfo account = visible(caller, accountId);
        Instant now = clock.instant();
        List<AccountLimit> configured = persistence.findByAccount(accountId);
        List<LimitView> views = new ArrayList<>();
        for (LimitScope scope : LimitScope.values()) {
            Optional<AccountLimit> found = configured.stream().filter(l -> l.scope() == scope).findFirst();
            Optional<Money> fallback = defaultFor(scope, account);
            if (found.isPresent()) {
                AccountLimit l = found.get();
                views.add(new LimitView(scope, l.effective(now), l.ceiling(), l.pendingAmount(), l.pendingEffectiveAt(), l.version(), false));
            } else if (fallback.isPresent()) {
                views.add(new LimitView(scope, fallback.get(), ceilingFor(account), null, null, null, true));
            }
        }
        return views;
    }

    @Override
    public AccountLimit change(Caller caller, UUID accountId, LimitScope scope, Money amount, Optional<ExpectedVersion> expected) {
        AccountInfo account = visible(caller, accountId);
        Instant now = clock.instant();
        Optional<AccountLimit> existing = persistence.find(accountId, scope);
        if (existing.isPresent()) {
            ExpectedVersion version = expected.orElseThrow(() ->
                    new PreconditionRequiredException("O cabeçalho If-Match é obrigatório para alterar um limite já configurado."));
            if (!version.matches(existing.get().version())) {
                throw new PreconditionFailedException("O limite foi alterado por outra requisição (versão desatualizada).");
            }
            return persistence.save(existing.get().requestChange(amount, now, ceilings.increaseDelayMin()));
        }
        // primeira configuração: parte do padrão vigente, e a mesma regra de carência/teto vale a partir dele
        Money start = defaultFor(scope, account).orElse(amount);
        AccountLimit initial = AccountLimit.initial(ids.newId(), accountId, scope, start, ceilingFor(account));
        return persistence.save(initial.requestChange(amount, now, ceilings.increaseDelayMin()));
    }

    private Optional<Money> defaultFor(LimitScope scope, AccountInfo account) {
        return switch (scope) {
            case DAILY -> Optional.of(account.isIndividual() ? defaults.defaultDailyIndividual() : defaults.defaultDailyBusiness());
            case NIGHTLY -> account.isIndividual() ? Optional.of(defaults.nightIndividual()) : Optional.empty();   // PJ não é afetada
            case DEVICE_UNREGISTERED_PER_TX -> Optional.of(defaults.unregisteredDevicePerTx());
            case DEVICE_UNREGISTERED_DAILY -> Optional.of(defaults.unregisteredDeviceDaily());
            case PER_TRANSACTION, NEW_PAYEE -> Optional.empty();
        };
    }

    private Money ceilingFor(AccountInfo account) {
        return account.isIndividual() ? ceilings.individual() : ceilings.business();
    }

    private AccountInfo visible(Caller caller, UUID accountId) {
        if (!caller.canAccess(accountId)) {
            throw new NotFoundException(ErrorCodes.NOT_FOUND, "Conta não encontrada.");
        }
        return accounts.findById(accountId).orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND, "Conta não encontrada."));
    }
}
