package br.com.pixmanager.core.application.service.pixkey;

import br.com.pixmanager.core.domain.entity.AccountInfo;
import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.PixKey;
import br.com.pixmanager.core.domain.entity.PixKeyValue;
import br.com.pixmanager.core.domain.entity.event.PixKeyEvent;
import br.com.pixmanager.core.domain.enums.AccountStatus;
import br.com.pixmanager.core.domain.enums.HolderType;
import br.com.pixmanager.core.domain.enums.PixKeyStatus;
import br.com.pixmanager.core.domain.enums.PixKeyType;
import br.com.pixmanager.core.port.in.account.FindAccountUseCase;
import br.com.pixmanager.core.port.out.event.DomainEventPublisherPortOut;
import br.com.pixmanager.core.port.out.persistence.PixKeyPersistencePortOut;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.shared.exception.ConflictException;
import br.com.pixmanager.shared.exception.NotFoundException;
import br.com.pixmanager.shared.model.document.Cnpj;
import br.com.pixmanager.shared.model.document.Cpf;
import br.com.pixmanager.shared.model.document.Document;
import br.com.pixmanager.support.FixedClock;
import br.com.pixmanager.support.SequentialIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RegisterPixKeyService — BR-KEY-01..05: titularidade, limite por conta, unicidade e estado inicial por tipo")
class RegisterPixKeyServiceTest {

    static final String HOLDER_CPF = "52998224725";
    static final String HOLDER_CNPJ = "11222333000181";
    static final int MAX_INDIVIDUAL = 5;
    static final int MAX_BUSINESS = 20;

    @Mock PixKeyPersistencePortOut persistence;
    @Mock FindAccountUseCase accounts;
    @Mock DomainEventPublisherPortOut events;

    final UUID accountId = SequentialIds.id(10);
    final Caller caller = Caller.forAccounts("client", accountId);
    RegisterPixKeyService service;

    @BeforeEach
    void setUp() {
        service = new RegisterPixKeyService(persistence, accounts, events, new SequentialIds(),
                FixedClock.at("2026-10-15T15:00:00Z"), MAX_INDIVIDUAL, MAX_BUSINESS);
    }

    @ParameterizedTest(name = "{0} ⇒ {1}")
    @CsvSource({
            "CPF,   PENDING_DICT",
            "EVP,   PENDING_DICT",
            "EMAIL, PENDING_OWNERSHIP_PROOF",
            "PHONE, PENDING_OWNERSHIP_PROOF"})
    @DisplayName("BR-KEY-04: estado inicial depende da exigência de prova de posse")
    void shouldStartInTheStateRequiredByTheKeyType(PixKeyType type, PixKeyStatus expected) {
        account(HolderType.INDIVIDUAL, AccountStatus.ACTIVE);
        when(persistence.countLiveByAccount(accountId)).thenReturn(0L);
        when(persistence.findLiveByValue(any(PixKeyValue.class))).thenReturn(Optional.empty());
        when(persistence.save(any(PixKey.class))).thenAnswer(inv -> inv.getArgument(0));

        PixKey key = service.register(caller, accountId, type, rawValueFor(type));

        assertThat(key.status()).isEqualTo(expected);
        assertThat(key.accountId()).isEqualTo(accountId);
        var event = ArgumentCaptor.forClass(PixKeyEvent.class);
        verify(events).publish(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("PixKeyRegistered");
    }

    @Test
    @DisplayName("EVP: o valor é gerado pelo PSP; o informado pelo cliente é ignorado")
    void shouldGenerateTheEvpValueIgnoringTheInformedOne() {
        account(HolderType.INDIVIDUAL, AccountStatus.ACTIVE);
        when(persistence.countLiveByAccount(accountId)).thenReturn(0L);
        when(persistence.findLiveByValue(any(PixKeyValue.class))).thenReturn(Optional.empty());
        when(persistence.save(any(PixKey.class))).thenAnswer(inv -> inv.getArgument(0));

        PixKey key = service.register(caller, accountId, PixKeyType.EVP, "valor-do-cliente");

        assertThat(key.value().value()).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void shouldAcceptTheCnpjOfABusinessHolder() {
        account(HolderType.BUSINESS, AccountStatus.ACTIVE);
        when(persistence.countLiveByAccount(accountId)).thenReturn(0L);
        when(persistence.findLiveByValue(any(PixKeyValue.class))).thenReturn(Optional.empty());
        when(persistence.save(any(PixKey.class))).thenAnswer(inv -> inv.getArgument(0));

        PixKey key = service.register(caller, accountId, PixKeyType.CNPJ, HOLDER_CNPJ);

        assertThat(key.value().value()).isEqualTo(HOLDER_CNPJ);
    }

    @ParameterizedTest(name = "titular {0} registrando {1} {2}")
    @CsvSource({
            "INDIVIDUAL, CNPJ, 11222333000181",   // PF não pode ter chave CNPJ
            "INDIVIDUAL, CPF,  11144477735",      // CPF de terceiro
            "BUSINESS,   CPF,  52998224725",      // PJ não pode ter chave CPF
            "BUSINESS,   CNPJ, 45723174000110"})  // CNPJ de terceiro
    @DisplayName("BR-KEY-02 / BR-DOC-06: chave de documento só do próprio titular")
    void shouldRejectDocumentKeysThatAreNotTheHolders(HolderType holder, PixKeyType type, String value) {
        account(holder, AccountStatus.ACTIVE);

        assertThatThrownBy(() -> service.register(caller, accountId, type, value))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getCode()).isEqualTo(ErrorCodes.KEY_NOT_OWNED));

        verify(persistence, never()).save(any());
        verifyNoInteractions(events);
    }

    @ParameterizedTest(name = "titular {0}: {1} chaves vivas ⇒ recusa")
    @CsvSource({"INDIVIDUAL, 5", "BUSINESS, 20"})
    @DisplayName("BR-KEY-03: limite de chaves por conta (PF 5 / PJ 20)")
    void shouldRejectWhenTheAccountReachedTheKeyLimit(HolderType holder, long live) {
        account(holder, AccountStatus.ACTIVE);
        when(persistence.countLiveByAccount(accountId)).thenReturn(live);

        assertThatThrownBy(() -> service.register(caller, accountId, PixKeyType.EVP, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getCode()).isEqualTo(ErrorCodes.KEY_LIMIT_EXCEEDED));

        verify(persistence, never()).save(any());
    }

    @Test
    @DisplayName("Uma a menos que o limite ainda é aceita")
    void shouldAcceptTheLastKeyBelowTheLimit() {
        account(HolderType.INDIVIDUAL, AccountStatus.ACTIVE);
        when(persistence.countLiveByAccount(accountId)).thenReturn((long) MAX_INDIVIDUAL - 1);
        when(persistence.findLiveByValue(any(PixKeyValue.class))).thenReturn(Optional.empty());
        when(persistence.save(any(PixKey.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.register(caller, accountId, PixKeyType.CPF, HOLDER_CPF)).isNotNull();
    }

    @Test
    @DisplayName("BR-DOC-04: valor já registrado em qualquer conta ⇒ conflito")
    void shouldRejectAKeyThatIsAlreadyLive() {
        account(HolderType.INDIVIDUAL, AccountStatus.ACTIVE);
        when(persistence.countLiveByAccount(accountId)).thenReturn(0L);
        when(persistence.findLiveByValue(PixKeyValue.of(PixKeyType.CPF, HOLDER_CPF)))
                .thenReturn(Optional.of(PixKey.register(SequentialIds.id(77), SequentialIds.id(11), PixKeyValue.of(PixKeyType.CPF, HOLDER_CPF),
                        FixedClock.at("2026-10-01T00:00:00Z").instant())));

        assertThatThrownBy(() -> service.register(caller, accountId, PixKeyType.CPF, HOLDER_CPF))
                .isInstanceOf(ConflictException.class);

        verify(persistence, never()).save(any());
        verifyNoInteractions(events);
    }

    @Test
    void shouldHideAnAccountOutsideTheCallerScope() {
        Caller other = Caller.forAccounts("other", SequentialIds.id(99));

        assertThatThrownBy(() -> service.register(other, accountId, PixKeyType.CPF, HOLDER_CPF))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(accounts, persistence, events);
    }

    @Test
    void shouldReturnNotFoundWhenTheAccountDoesNotExist() {
        when(accounts.findById(accountId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(caller, accountId, PixKeyType.CPF, HOLDER_CPF))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void shouldRejectAnInactiveAccountBeforeTouchingTheKeyStore() {
        account(HolderType.INDIVIDUAL, AccountStatus.BLOCKED);

        assertThatThrownBy(() -> service.register(caller, accountId, PixKeyType.CPF, HOLDER_CPF))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getCode()).isEqualTo(ErrorCodes.KEY_NOT_ACTIVE));

        verifyNoInteractions(persistence, events);
    }

    @Test
    void shouldRejectAMalformedKeyValue() {
        account(HolderType.INDIVIDUAL, AccountStatus.ACTIVE);

        assertThatThrownBy(() -> service.register(caller, accountId, PixKeyType.EMAIL, "Nao-e-Email"))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getCode()).isEqualTo(ErrorCodes.INVALID_PIX_KEY));

        verifyNoInteractions(events);
    }

    // ------------------------------------------------------------------ helpers

    private void account(HolderType holder, AccountStatus status) {
        Document document = holder == HolderType.INDIVIDUAL ? Cpf.of(HOLDER_CPF) : Cnpj.of(HOLDER_CNPJ);
        when(accounts.findById(accountId)).thenReturn(Optional.of(new AccountInfo(accountId, SequentialIds.id(20), holder, document,
                "hash", "Titular", "12345678", "0001", "1", "CACC", status)));
    }

    private static String rawValueFor(PixKeyType type) {
        return switch (type) {
            case CPF -> HOLDER_CPF;
            case EMAIL -> "titular@example.com";
            case PHONE -> "+5511999998888";
            case EVP, CNPJ -> null;
        };
    }
}
