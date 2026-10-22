package br.com.pixmanager.core.application.service.pixkey;

import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.OtpChallenge;
import br.com.pixmanager.core.domain.entity.PixKey;
import br.com.pixmanager.core.domain.entity.PixKeyValue;
import br.com.pixmanager.core.domain.enums.OtpChannel;
import br.com.pixmanager.core.domain.enums.PixKeyStatus;
import br.com.pixmanager.core.domain.enums.PixKeyType;
import br.com.pixmanager.core.port.in.pixkey.OwnershipProofUseCase.Issued;
import br.com.pixmanager.core.port.out.OtpCodePortOut;
import br.com.pixmanager.core.port.out.OtpSenderPortOut;
import br.com.pixmanager.core.port.out.persistence.OtpChallengePortOut;
import br.com.pixmanager.core.port.out.persistence.PixKeyPersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.TransactionRunnerPortOut;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.shared.exception.IntegrationException;
import br.com.pixmanager.shared.exception.NotFoundException;
import br.com.pixmanager.shared.exception.TooManyRequestsException;
import br.com.pixmanager.shared.model.dto.PageQuery;
import br.com.pixmanager.shared.model.dto.PageResult;
import br.com.pixmanager.shared.properties.PixPolicyProperties;
import br.com.pixmanager.support.FixedClock;
import br.com.pixmanager.support.SequentialIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Prova de posse por OTP (BR-KEY-04)")
class OwnershipProofServiceTest {

    static final UUID ACCOUNT = SequentialIds.id(900);
    static final PixKeyPersistencePortOut NOT_USED = null;

    // ---- dublês em memória (sem Mockito: o comportamento é o que importa)
    final Map<UUID, PixKey> keys = new HashMap<>();
    final List<OtpChallenge> challenges = new ArrayList<>();
    final List<String> sent = new ArrayList<>();
    final Deque<String> nextCodes = new ArrayDeque<>();
    boolean senderFails;

    FixedClock clock;
    OwnershipProofService service;
    PixKey key;
    final Caller owner = Caller.forAccounts("cli", ACCOUNT);

    final PixKeyPersistencePortOut keyPort = new PixKeyPersistencePortOut() {
        public PixKey save(PixKey pixKey) { PixKey next = new PixKey(pixKey.id(), pixKey.accountId(), pixKey.value(), pixKey.status(), pixKey.dictCid(), pixKey.dictRegisteredAt(),
                pixKey.deletedReason(), pixKey.deletedAt(), pixKey.version() + 1, pixKey.createdAt()); keys.put(pixKey.id(), next); return next; }
        public Optional<PixKey> findById(UUID id) { return Optional.ofNullable(keys.get(id)); }
        public Optional<PixKey> findLiveByValue(PixKeyValue pixKeyValue) { return Optional.empty(); }
        public long countLiveByAccount(UUID accountId) { return 0; }
        public PageResult<PixKey> list(UUID accountId, Optional<PixKeyStatus> pixKeyStatus, PageQuery pageQuery) { return new PageResult<>(List.of(), 0, 20, 0); }
        public List<PixKey> findPendingDict(Instant olderThan, int limit) { return List.of(); }
    };
    final OtpChallengePortOut challengePort = new OtpChallengePortOut() {
        public OtpChallenge save(OtpChallenge otpChallenge) { challenges.removeIf(existing -> existing.id().equals(otpChallenge.id())); challenges.add(otpChallenge); return otpChallenge; }
        public Optional<OtpChallenge> findLatest(UUID keyId) { return challenges.stream().filter(otpChallenge -> otpChallenge.pixKeyId().equals(keyId))
                .max(java.util.Comparator.comparing(OtpChallenge::createdAt)); }
        public long countSince(UUID keyId, Instant since) { return challenges.stream().filter(otpChallenge -> otpChallenge.pixKeyId().equals(keyId) && !otpChallenge.createdAt().isBefore(since)).count(); }
    };
    final OtpCodePortOut codePort = new OtpCodePortOut() {
        public String generate() { return nextCodes.removeFirst(); }
        public String hash(UUID keyId, String code) { return "h(" + keyId + ":" + code + ")"; }
    };
    final OtpSenderPortOut senderPort = (channel, destination, code, validFor) -> {
        if (senderFails) throw new IntegrationException(ErrorCodes.DEPENDENCY_FAILURE, "gateway fora", null);
        sent.add(channel + "|" + destination + "|" + code);
    };
    final TransactionRunnerPortOut transactionRunner = new TransactionRunnerPortOut() {
        public <T> T inTransaction(Supplier<T> action) { return action.get(); }
    };

    @BeforeEach
    void setUp() {
        clock = FixedClock.at("2026-10-16T12:00:00Z");
        service = new OwnershipProofService(keyPort, challengePort, codePort, senderPort, new SequentialIds(), transactionRunner, clock,
                new PixPolicyProperties.Otp(Duration.ofMinutes(5), 3, Duration.ofSeconds(60), 3));
        key = keyPort.save(PixKey.register(SequentialIds.id(1), ACCOUNT, PixKeyValue.of(PixKeyType.EMAIL, "maria@example.com"), clock.instant()));
        assertThat(key.status()).isEqualTo(PixKeyStatus.PENDING_OWNERSHIP_PROOF);
    }

    @Test
    @DisplayName("emitir: envia o código ao e-mail da chave, guarda SÓ o hash, devolve destino mascarado e validade — e nunca o código")
    void issueSendsAndStoresOnlyTheHash() {
        nextCodes.add("123456");

        Issued issued = service.issue(owner, key.id());

        assertThat(sent).containsExactly("EMAIL|maria@example.com|123456");
        assertThat(challenges).hasSize(1);
        OtpChallenge stored = challenges.get(0);
        assertThat(stored.codeHash()).isEqualTo("h(" + key.id() + ":123456)").doesNotContain("123456 ").isNotEqualTo("123456");
        assertThat(stored.attempts()).isZero();
        assertThat(issued.channel()).isEqualTo(OtpChannel.EMAIL);
        assertThat(issued.destinationMasked()).doesNotContain("maria@example.com");
        assertThat(issued.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(5)));
        assertThat(issued.toString()).doesNotContain("123456");
    }

    @Test
    @DisplayName("telefone usa o canal SMS")
    void phoneUsesSms() {
        PixKey phone = keyPort.save(PixKey.register(SequentialIds.id(2), ACCOUNT, PixKeyValue.of(PixKeyType.PHONE, "+5511987654321"), clock.instant()));
        nextCodes.add("000042");
        assertThat(service.issue(owner, phone.id()).channel()).isEqualTo(OtpChannel.SMS);
        assertThat(sent).containsExactly("SMS|+5511987654321|000042");
    }

    @Test
    @DisplayName("espera mínima entre pedidos: dentro dela ⇒ 429 com o tempo restante; depois dela, emite")
    void resendCooldown() {
        nextCodes.add("111111");
        nextCodes.add("222222");
        service.issue(owner, key.id());
        clock.advance(Duration.ofSeconds(20));

        assertThatThrownBy(() -> service.issue(owner, key.id())).isInstanceOfSatisfying(TooManyRequestsException.class, exception -> {
            assertThat(exception.getCode()).isEqualTo(ErrorCodes.OTP_RATE_LIMITED);
            assertThat(exception.getRetryAfter()).isEqualTo(Duration.ofSeconds(40));
        });
        assertThat(sent).hasSize(1);

        clock.advance(Duration.ofSeconds(40));
        service.issue(owner, key.id());
        assertThat(sent).hasSize(2);
    }

    @Test
    @DisplayName("teto de códigos por chave em 24 h (3): o 4º é recusado mesmo respeitando a espera; no dia seguinte volta")
    void dailyCap() {
        for (int index = 0; index < 3; index++) {
            nextCodes.add("10000" + index);
            service.issue(owner, key.id());
            clock.advance(Duration.ofSeconds(61));
        }
        assertThatThrownBy(() -> service.issue(owner, key.id())).isInstanceOf(TooManyRequestsException.class)
                .hasMessageContaining("diário");
        clock.advance(Duration.ofHours(24));
        nextCodes.add("999999");
        assertThat(service.issue(owner, key.id())).isNotNull();
    }

    @Test
    @DisplayName("só chave aguardando prova de posse: ACTIVE ⇒ 422; chave de outra conta ou inexistente ⇒ 404 (sem vazar existência)")
    void onlyPendingOwnershipKeysOfTheCaller() {
        PixKey active = keyPort.save(new PixKey(SequentialIds.id(3), ACCOUNT, PixKeyValue.of(PixKeyType.EMAIL, "x@example.com"), PixKeyStatus.ACTIVE,
                "cid", clock.instant(), null, null, 0, clock.instant()));
        assertThatThrownBy(() -> service.issue(owner, active.id())).isInstanceOf(BusinessException.class).hasMessageContaining("não está aguardando");
        assertThatThrownBy(() -> service.issue(Caller.forAccounts("cli", SequentialIds.id(777)), key.id())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.issue(owner, SequentialIds.id(404))).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.verify(Caller.forAccounts("cli", SequentialIds.id(777)), key.id(), "123456")).isInstanceOf(NotFoundException.class);
        assertThat(sent).isEmpty();
    }

    @Test
    @DisplayName("verificar certo: chave vai a PENDING_DICT (o job registra no DICT), desafio marcado verificado; repetir o código não funciona")
    void verifySuccess() {
        nextCodes.add("123456");
        service.issue(owner, key.id());

        PixKey proven = service.verify(owner, key.id(), "123456");

        assertThat(proven.status()).isEqualTo(PixKeyStatus.PENDING_DICT);
        assertThat(keys.get(key.id()).status()).isEqualTo(PixKeyStatus.PENDING_DICT);
        assertThat(challenges.get(0).isVerified()).isTrue();
        assertThatThrownBy(() -> service.verify(owner, key.id(), "123456")).isInstanceOf(BusinessException.class)
                .hasMessageContaining("não está aguardando");
    }

    @Test
    @DisplayName("código errado conta tentativa (com as restantes na mensagem); esgotadas, até o código CERTO é recusado e exige novo pedido")
    void wrongCodeBurnsAttempts() {
        nextCodes.add("123456");
        service.issue(owner, key.id());

        assertThatThrownBy(() -> service.verify(owner, key.id(), "000000")).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.getCode()).isEqualTo(ErrorCodes.OTP_INVALID);
            assertThat(exception.getMessage()).contains("Restam 2");
        });
        assertThatThrownBy(() -> service.verify(owner, key.id(), "000001")).hasMessageContaining("Restam 1");
        assertThatThrownBy(() -> service.verify(owner, key.id(), "000002")).hasMessageContaining("esgotadas");
        assertThat(challenges.get(0).attempts()).isEqualTo(3);

        assertThatThrownBy(() -> service.verify(owner, key.id(), "123456")).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getCode()).isEqualTo(ErrorCodes.OTP_ATTEMPTS_EXCEEDED));
        assertThat(keys.get(key.id()).status()).isEqualTo(PixKeyStatus.PENDING_OWNERSHIP_PROOF);

        clock.advance(Duration.ofSeconds(61));                      // novo pedido gera novo desafio, com tentativas zeradas
        nextCodes.add("654321");
        service.issue(owner, key.id());
        assertThat(service.verify(owner, key.id(), "654321").status()).isEqualTo(PixKeyStatus.PENDING_DICT);
    }

    @Test
    @DisplayName("código vencido (após 5 min) ⇒ OTP_EXPIRED mesmo estando certo; só vale o desafio MAIS RECENTE")
    void expiredAndSuperseded() {
        nextCodes.add("111111");
        service.issue(owner, key.id());
        clock.advance(Duration.ofMinutes(5));
        assertThatThrownBy(() -> service.verify(owner, key.id(), "111111")).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getCode()).isEqualTo(ErrorCodes.OTP_EXPIRED));

        nextCodes.add("222222");
        service.issue(owner, key.id());
        assertThatThrownBy(() -> service.verify(owner, key.id(), "111111")).hasMessageContaining("Restam");   // o código antigo não vale mais
        assertThat(service.verify(owner, key.id(), "222222").status()).isEqualTo(PixKeyStatus.PENDING_DICT);
    }

    @Test
    @DisplayName("verificar sem nunca ter pedido código ⇒ OTP_INVALID")
    void verifyWithoutChallenge() {
        assertThatThrownBy(() -> service.verify(owner, key.id(), "123456")).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getCode()).isEqualTo(ErrorCodes.OTP_INVALID));
    }

    @Test
    @DisplayName("falha do gateway de envio ⇒ erro técnico propagado (502); o desafio fica gravado e o cliente pede outro após a espera")
    void senderFailurePropagates() {
        nextCodes.add("123456");
        senderFails = true;
        assertThatThrownBy(() -> service.issue(owner, key.id())).isInstanceOf(IntegrationException.class);
        assertThat(challenges).hasSize(1);
        assertThat(sent).isEmpty();
    }
}
