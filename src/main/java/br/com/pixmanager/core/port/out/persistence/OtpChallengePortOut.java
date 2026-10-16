package br.com.pixmanager.core.port.out.persistence;

import br.com.pixmanager.core.domain.entity.OtpChallenge;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OtpChallengePortOut {

    /** Insere ou atualiza (tentativas / verificação). */
    OtpChallenge save(OtpChallenge challenge);

    /** O desafio mais recente da chave (verificado ou não). */
    Optional<OtpChallenge> findLatest(UUID pixKeyId);

    /** Quantos desafios foram emitidos para a chave desde {@code since} (limite diário). */
    long countSince(UUID pixKeyId, Instant since);
}
