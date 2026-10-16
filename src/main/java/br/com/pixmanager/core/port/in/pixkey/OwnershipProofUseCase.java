package br.com.pixmanager.core.port.in.pixkey;

import br.com.pixmanager.core.domain.entity.PixKey;
import br.com.pixmanager.core.domain.enums.OtpChannel;
import br.com.pixmanager.core.domain.entity.Caller;

import java.time.Instant;
import java.util.UUID;

/** Prova de posse (OTP) de chave PHONE/EMAIL (BR-KEY-04): emitir o desafio e verificar o código. */
public interface OwnershipProofUseCase {

    /** Gera um código, grava só o hash e o envia ao dono do e-mail/telefone. */
    Issued issue(Caller caller, UUID keyId);

    /** Confere o código; acertando, a chave sai de PENDING_OWNERSHIP_PROOF para PENDING_DICT. */
    PixKey verify(Caller caller, UUID keyId, String code);

    record Issued(UUID challengeId, OtpChannel channel, String destinationMasked, Instant expiresAt) {}
}
