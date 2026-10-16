package br.com.pixmanager.api.rest.pixkey.dto.response;

import br.com.pixmanager.core.domain.enums.OtpChannel;

import java.time.Instant;
import java.util.UUID;

/** O código NUNCA volta na resposta: só chega ao dono do canal. {@code destination} sai mascarado. */
public record OwnershipChallengeResponse(UUID challengeId, OtpChannel channel, String destination, Instant expiresAt) {}
