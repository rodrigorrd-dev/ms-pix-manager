package br.com.pixmanager.core.application.service.limit;

import br.com.pixmanager.core.domain.entity.AccountInfo;
import br.com.pixmanager.core.domain.entity.AccountLimit;
import br.com.pixmanager.core.domain.entity.LimitDecision;
import br.com.pixmanager.core.domain.entity.LimitPolicy;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.enums.AccountStatus;
import br.com.pixmanager.core.domain.enums.HolderType;
import br.com.pixmanager.core.domain.enums.LimitScope;
import br.com.pixmanager.core.port.in.account.FindAccountUseCase;
import br.com.pixmanager.core.port.out.persistence.LimitPersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.LimitUsagePortOut;
import br.com.pixmanager.shared.model.document.Cpf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConsumeLimitService — diário atômico, compensação do diário geral quando o do dispositivo estoura")
class ConsumeLimitServiceTest {

    static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    static final LimitPolicy.Config CFG = new LimitPolicy.Config(SP, LocalTime.of(20, 0), LocalTime.of(6, 0), Money.of("1000.00"),
            Money.of("200.00"), Money.of("1000.00"), Money.of("5000.00"), Money.of("50000.00"));
    static final Instant DAYTIME = Instant.parse("2026-10-15T15:00:00Z");    // 12:00 em SP

    UUID accountId = UUID.randomUUID();
    HolderType holder = HolderType.INDIVIDUAL;
    InMemoryUsage usage = new InMemoryUsage();
    Map<LimitScope, AccountLimit> configured = new HashMap<>();
    ConsumeLimitService service;

    @BeforeEach
    void setUp() {
        LimitPersistencePortOut limits = new LimitPersistencePortOut() {
            public List<AccountLimit> findByAccount(UUID id) { return List.copyOf(configured.values()); }
            public Optional<AccountLimit> find(UUID id, LimitScope scope) { return Optional.ofNullable(configured.get(scope)); }
            public AccountLimit save(AccountLimit accountLimit) { configured.put(accountLimit.scope(), accountLimit); return accountLimit; }
        };
        FindAccountUseCase accounts = new FindAccountUseCase() {
            public Optional<AccountInfo> findById(UUID id) {
                return Optional.of(new AccountInfo(accountId, UUID.randomUUID(), holder, Cpf.of("52998224725"), "hash", "Titular", "12345678",
                        "0001", "1", "CACC", AccountStatus.ACTIVE));
            }
            public Optional<AccountInfo> findByIdentity(String ispb, String branch, String number, String accountType) { return Optional.empty(); }
        };
        service = new ConsumeLimitService(limits, usage, accounts, CFG);
    }

    @Test
    void consumesDailyAndRejectsWhenTheDayIsExhausted() {
        assertThat(service.consume(accountId, Money.of("3000.00"), DAYTIME, true)).isInstanceOf(LimitDecision.Allowed.class);
        assertThat(service.consume(accountId, Money.of("2000.00"), DAYTIME, true)).isInstanceOf(LimitDecision.Allowed.class);
        assertThat(service.consume(accountId, Money.of("0.01"), DAYTIME, true))
                .isEqualTo(new LimitDecision.Rejected(LimitScope.DAILY, Money.of("5000.00")));
        assertThat(usage.used(LimitScope.DAILY)).isEqualTo(Money.of("5000.00"));
    }

    @Test
    void nextDayStartsFromZero() {
        service.consume(accountId, Money.of("5000.00"), DAYTIME, true);
        assertThat(service.consume(accountId, Money.of("100.00"), DAYTIME.plusSeconds(24 * 3600), true)).isInstanceOf(LimitDecision.Allowed.class);
    }

    @Test
    @DisplayName("Dispositivo não cadastrado: R$1.000/dia; ao estourar, o diário GERAL consumido é devolvido (compensação)")
    void deviceDailyRejectionReleasesTheGeneralDaily() {
        assertThat(service.consume(accountId, Money.of("200.00"), DAYTIME, false)).isInstanceOf(LimitDecision.Allowed.class);
        for (int index = 0; index < 4; index++) {
            assertThat(service.consume(accountId, Money.of("200.00"), DAYTIME, false)).isInstanceOf(LimitDecision.Allowed.class);
        }
        // 5 × 200 = 1000 consumidos; o próximo estoura o do dispositivo
        assertThat(service.consume(accountId, Money.of("200.00"), DAYTIME, false))
                .isEqualTo(new LimitDecision.Rejected(LimitScope.DEVICE_UNREGISTERED_DAILY, Money.of("1000.00")));
        assertThat(usage.used(LimitScope.DAILY)).as("o diário geral foi devolvido").isEqualTo(Money.of("1000.00"));
    }

    @Test
    void releaseGivesTheAmountBack() {
        var allowed = (LimitDecision.Allowed) service.consume(accountId, Money.of("150.00"), DAYTIME, false);
        service.release(accountId, allowed);
        assertThat(usage.used(LimitScope.DAILY)).isEqualTo(Money.ZERO);
        assertThat(usage.used(LimitScope.DEVICE_UNREGISTERED_DAILY)).isEqualTo(Money.ZERO);
    }

    @Test
    void customerConfiguredDailyLimitIsRespectedOnlyOnceEffective() {
        AccountLimit raised = AccountLimit.initial(UUID.randomUUID(), accountId, LimitScope.DAILY, Money.of("5000.00"), Money.of("50000.00"))
                .requestChange(Money.of("9000.00"), DAYTIME.minusSeconds(3600), java.time.Duration.ofHours(24));
        configured.put(LimitScope.DAILY, raised);
        assertThat(service.consume(accountId, Money.of("6000.00"), DAYTIME, true)).as("aumento ainda em carência").isInstanceOf(LimitDecision.Rejected.class);
        assertThat(service.consume(accountId, Money.of("6000.00"), DAYTIME.plusSeconds(24 * 3600), true)).isInstanceOf(LimitDecision.Allowed.class);
    }

    static class InMemoryUsage implements LimitUsagePortOut {
        final Map<String, Money> used = new HashMap<>();

        public synchronized boolean tryConsume(UUID account, LocalDate day, LimitScope scope, Money amount, Money max) {
            String key = day + "|" + scope;
            Money next = used.getOrDefault(key, Money.ZERO).plus(amount);
            if (next.isGreaterThan(max)) {
                return false;
            }
            used.put(key, next);
            return true;
        }

        public synchronized void release(UUID account, LocalDate day, LimitScope scope, Money amount) {
            used.merge(day + "|" + scope, Money.ZERO, (existing, incoming) -> existing.minus(amount));
        }

        Money used(LimitScope scope) {
            return used.entrySet().stream().filter(entry -> entry.getKey().endsWith("|" + scope)).map(Map.Entry::getValue)
                    .reduce(Money.ZERO, Money::plus);
        }
    }
}
