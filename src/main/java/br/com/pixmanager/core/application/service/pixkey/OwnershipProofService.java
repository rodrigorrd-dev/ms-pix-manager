package br.com.pixmanager.core.application.service.pixkey;

import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.OtpChallenge;
import br.com.pixmanager.core.domain.entity.PixKey;
import br.com.pixmanager.core.domain.enums.OtpChannel;
import br.com.pixmanager.core.domain.enums.PixKeyStatus;
import br.com.pixmanager.core.port.in.pixkey.OwnershipProofUseCase;
import br.com.pixmanager.core.port.out.OtpCodePortOut;
import br.com.pixmanager.core.port.out.OtpSenderPortOut;
import br.com.pixmanager.core.port.out.generator.IdGeneratorPortOut;
import br.com.pixmanager.core.port.out.persistence.OtpChallengePortOut;
import br.com.pixmanager.core.port.out.persistence.PixKeyPersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.TransactionRunnerPortOut;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.shared.exception.NotFoundException;
import br.com.pixmanager.shared.exception.TooManyRequestsException;
import br.com.pixmanager.shared.properties.PixPolicyProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Prova de posse de chave PHONE/EMAIL por código de uso único (BR-KEY-04).
 *
 * <p>Segurança: o código é aleatório de 6 dígitos, guardado só como HMAC ligado à chave, comparado em tempo constante, vale poucos minutos,
 * tem poucas tentativas e há espera entre pedidos e teto diário — o que torna inviável adivinhar por força bruta (1 em 10⁶ por tentativa,
 * 5 tentativas por código, 10 códigos por dia). O envio (HTTP) acontece FORA de transação; a gravação do desafio e a mudança de estado da
 * chave são atômicas.
 */
public class OwnershipProofService implements OwnershipProofUseCase {

    private final PixKeyPersistencePortOut keys;
    private final OtpChallengePortOut challenges;
    private final OtpCodePortOut codes;
    private final OtpSenderPortOut sender;
    private final IdGeneratorPortOut ids;
    private final TransactionRunnerPortOut tx;
    private final Clock clock;
    private final PixPolicyProperties.Otp policy;

    public OwnershipProofService(PixKeyPersistencePortOut keys, OtpChallengePortOut challenges, OtpCodePortOut codes, OtpSenderPortOut sender,
                                 IdGeneratorPortOut ids, TransactionRunnerPortOut tx, Clock clock, PixPolicyProperties.Otp policy) {
        this.keys = keys;
        this.challenges = challenges;
        this.codes = codes;
        this.sender = sender;
        this.ids = ids;
        this.tx = tx;
        this.clock = clock;
        this.policy = policy;
    }

    @Override
    public Issued issue(Caller caller, UUID keyId) {
        PixKey key = awaitingProof(caller, keyId);
        Instant now = clock.instant();

        Optional<OtpChallenge> latest = challenges.findLatest(keyId);
        if (latest.isPresent()) {
            Instant next = latest.get().createdAt().plus(policy.resendCooldown());
            if (now.isBefore(next)) {
                throw new TooManyRequestsException(ErrorCodes.OTP_RATE_LIMITED,
                        "Aguarde antes de pedir um novo código.", Duration.between(now, next));
            }
        }
        if (challenges.countSince(keyId, now.minus(Duration.ofHours(24))) >= policy.maxPerDay()) {
            throw new TooManyRequestsException(ErrorCodes.OTP_RATE_LIMITED,
                    "Limite diário de códigos para esta chave atingido.", Duration.ofHours(1));
        }

        String code = codes.generate();
        OtpChannel channel = OtpChannel.forKeyType(key.value().type());
        OtpChallenge challenge = OtpChallenge.issue(ids.newId(), keyId, channel, codes.hash(keyId, code), now, policy.ttl());
        tx.inTransaction(() -> challenges.save(challenge));
        // fora de transação: se o envio falhar (502), o desafio fica sem uso e vence; o cliente pede outro depois da espera
        sender.send(channel, key.value().value(), code, policy.ttl());
        return new Issued(challenge.id(), channel, key.value().masked(), challenge.expiresAt());
    }

    @Override
    public PixKey verify(Caller caller, UUID keyId, String code) {
        PixKey key = awaitingProof(caller, keyId);
        Instant now = clock.instant();

        OtpChallenge challenge = challenges.findLatest(keyId).filter(c -> !c.isVerified())
                .orElseThrow(() -> new BusinessException(ErrorCodes.OTP_INVALID, "Nenhum código pendente: peça um novo código."));
        if (challenge.isExpired(now)) {
            throw new BusinessException(ErrorCodes.OTP_EXPIRED, "O código venceu: peça um novo código.");
        }
        if (challenge.attempts() >= policy.maxAttempts()) {
            throw new BusinessException(ErrorCodes.OTP_ATTEMPTS_EXCEEDED, "Tentativas esgotadas para este código: peça um novo código.");
        }
        if (!matches(codes.hash(keyId, code), challenge.codeHash())) {
            OtpChallenge failed = challenge.failedAttempt();
            tx.inTransaction(() -> challenges.save(failed));        // a tentativa é contada MESMO falhando
            int left = policy.maxAttempts() - failed.attempts();
            throw new BusinessException(ErrorCodes.OTP_INVALID, left > 0
                    ? "Código incorreto. Restam " + left + " tentativa(s)."
                    : "Código incorreto. Tentativas esgotadas: peça um novo código.");
        }
        return tx.inTransaction(() -> {
            challenges.save(challenge.verified(now));
            return keys.save(key.ownershipProven());                // PENDING_OWNERSHIP_PROOF → PENDING_DICT (o job registra no DICT)
        });
    }

    private PixKey awaitingProof(Caller caller, UUID keyId) {
        PixKey key = keys.findById(keyId).filter(k -> caller.canAccess(k.accountId()))
                .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND, "Chave não encontrada."));
        if (key.status() != PixKeyStatus.PENDING_OWNERSHIP_PROOF) {
            throw new BusinessException(ErrorCodes.BUSINESS_RULE, "A chave não está aguardando prova de posse (status " + key.status() + ").");
        }
        return key;
    }

    /** Comparação em tempo constante (não vaza, pelo tempo, quantos caracteres do hash batem). */
    private static boolean matches(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
