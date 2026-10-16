package br.com.pixmanager.shared.idempotency;

import java.util.UUID;

/** Resultado de {@link IdempotencyService#begin}. Resultado selado: falha esperada como valor, não exceção. */
public sealed interface IdempotencyOutcome {

    /** Primeira execução (ou tomada de lease expirado): execute e depois {@code complete}/{@code release}. */
    record Proceed(UUID recordId) implements IdempotencyOutcome {}

    /** Mesma chave e mesmo corpo já concluídos: devolva a resposta original. */
    record Replay(int status, String body) implements IdempotencyOutcome {}

    /** Mesma chave ainda em andamento (409 + Retry-After). */
    record InProgress() implements IdempotencyOutcome {}

    /** Mesma chave com corpo diferente (422). */
    record Mismatch() implements IdempotencyOutcome {}
}
