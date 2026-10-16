package br.com.pixmanager.core.application.service.limit;

import br.com.pixmanager.core.domain.entity.AccountInfo;
import br.com.pixmanager.core.domain.entity.AccountLimit;
import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.LimitPolicy;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.enums.AccountStatus;
import br.com.pixmanager.core.domain.enums.HolderType;
import br.com.pixmanager.core.domain.enums.LimitScope;
import br.com.pixmanager.core.port.in.account.FindAccountUseCase;
import br.com.pixmanager.core.port.in.limit.ManageLimitUseCase.LimitView;
import br.com.pixmanager.core.port.out.persistence.LimitPersistencePortOut;
import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.shared.exception.NotFoundException;
import br.com.pixmanager.shared.exception.PreconditionFailedException;
import br.com.pixmanager.shared.exception.PreconditionRequiredException;
import br.com.pixmanager.shared.model.ExpectedVersion;
import br.com.pixmanager.shared.model.document.Cpf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ManageLimitService — padrões, aumento com carência, redução imediata, teto e If-Match")
class ManageLimitServiceTest {

    static final Instant NOW = Instant.parse("2026-10-15T15:00:00Z");
    UUID accountId = UUID.randomUUID();
    HolderType holder = HolderType.INDIVIDUAL;
    Map<LimitScope, AccountLimit> configured = new HashMap<>();
    ManageLimitService service;
    Caller owner;

    @BeforeEach
    void setUp() {
        owner = Caller.forAccounts("cli", accountId);
        LimitPersistencePortOut persistence = new LimitPersistencePortOut() {
            public List<AccountLimit> findByAccount(UUID id) { return List.copyOf(configured.values()); }
            public Optional<AccountLimit> find(UUID id, LimitScope scope) { return Optional.ofNullable(configured.get(scope)); }
            public AccountLimit save(AccountLimit l) { configured.put(l.scope(), l); return l; }
        };
        FindAccountUseCase accounts = new FindAccountUseCase() {
            public Optional<AccountInfo> findById(UUID id) {
                return Optional.of(new AccountInfo(accountId, UUID.randomUUID(), holder, Cpf.of("52998224725"), "h", "Titular", "12345678",
                        "0001", "1", "CACC", AccountStatus.ACTIVE));
            }
            public Optional<AccountInfo> findByIdentity(String a, String b, String c, String d) { return Optional.empty(); }
        };
        LimitPolicy.Config defaults = new LimitPolicy.Config(ZoneId.of("America/Sao_Paulo"), LocalTime.of(20, 0), LocalTime.of(6, 0),
                Money.of("1000.00"), Money.of("200.00"), Money.of("1000.00"), Money.of("5000.00"), Money.of("50000.00"));
        service = new ManageLimitService(persistence, accounts, UUID::randomUUID, Clock.fixed(NOW, ZoneOffset.UTC),
                defaults, new ManageLimitService.Ceilings(Money.of("50000.00"), Money.of("500000.00"), Duration.ofHours(24)));
    }

    @Test
    @DisplayName("PF sem configuração: lista os padrões (diário, noturno, dispositivo); PJ não tem noturno")
    void listsDefaults() {
        List<LimitView> views = service.list(owner, accountId);
        assertThat(views).extracting(LimitView::scope).containsExactlyInAnyOrder(LimitScope.DAILY, LimitScope.NIGHTLY,
                LimitScope.DEVICE_UNREGISTERED_PER_TX, LimitScope.DEVICE_UNREGISTERED_DAILY);
        assertThat(views).allMatch(LimitView::isDefault);
        assertThat(views.stream().filter(v -> v.scope() == LimitScope.DAILY).findFirst().orElseThrow().effective()).isEqualTo(Money.of("5000.00"));

        holder = HolderType.BUSINESS;
        List<LimitView> pj = service.list(owner, accountId);
        assertThat(pj).extracting(LimitView::scope).doesNotContain(LimitScope.NIGHTLY);
        assertThat(pj.stream().filter(v -> v.scope() == LimitScope.DAILY).findFirst().orElseThrow().effective()).isEqualTo(Money.of("50000.00"));
    }

    @Test
    void reductionIsImmediateAndIncreaseWaitsForTheGracePeriod() {
        AccountLimit reduced = service.change(owner, accountId, LimitScope.DAILY, Money.of("2000.00"), Optional.empty());
        assertThat(reduced.effective(NOW)).isEqualTo(Money.of("2000.00"));
        assertThat(reduced.pendingAmount()).isNull();

        AccountLimit raised = service.change(owner, accountId, LimitScope.DAILY, Money.of("4000.00"), Optional.of(new ExpectedVersion.Any()));
        assertThat(raised.effective(NOW)).as("ainda vale o anterior").isEqualTo(Money.of("2000.00"));
        assertThat(raised.pendingAmount()).isEqualTo(Money.of("4000.00"));
        assertThat(raised.effective(NOW.plusSeconds(24 * 3600 + 1))).isEqualTo(Money.of("4000.00"));

        LimitView view = service.list(owner, accountId).stream().filter(v -> v.scope() == LimitScope.DAILY).findFirst().orElseThrow();
        assertThat(view.isDefault()).isFalse();
        assertThat(view.pendingAmount()).isEqualTo(Money.of("4000.00"));
        assertThat(view.pendingEffectiveAt()).isAfter(NOW);
    }

    @Test
    void changingAnExistingLimitRequiresAMatchingVersion() {
        service.change(owner, accountId, LimitScope.DAILY, Money.of("2000.00"), Optional.empty());
        assertThatThrownBy(() -> service.change(owner, accountId, LimitScope.DAILY, Money.of("1000.00"), Optional.empty()))
                .isInstanceOf(PreconditionRequiredException.class);
        assertThatThrownBy(() -> service.change(owner, accountId, LimitScope.DAILY, Money.of("1000.00"), Optional.of(new ExpectedVersion.Exact(99))))
                .isInstanceOf(PreconditionFailedException.class);
        long v = configured.get(LimitScope.DAILY).version();
        assertThat(service.change(owner, accountId, LimitScope.DAILY, Money.of("1000.00"), Optional.of(new ExpectedVersion.Exact(v))).amount())
                .isEqualTo(Money.of("1000.00"));
    }

    @Test
    void aboveTheBankCeilingIsRejected() {
        assertThatThrownBy(() -> service.change(owner, accountId, LimitScope.DAILY, Money.of("50000.01"), Optional.empty()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void foreignAccountLooksNonexistent() {
        Caller other = Caller.forAccounts("outro", UUID.randomUUID());
        assertThatThrownBy(() -> service.list(other, accountId)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.change(other, accountId, LimitScope.DAILY, Money.of("1.00"), Optional.empty())).isInstanceOf(NotFoundException.class);
    }
}
