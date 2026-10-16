package br.com.pixmanager.api.messaging;

import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.ConflictException;
import br.com.pixmanager.shared.exception.IntegrationException;
import br.com.pixmanager.shared.exception.PreconditionFailedException;

import java.util.function.Supplier;

/**
 * Num consumer, conflito de concorrência (versão otimista, corrida de unicidade) é TRANSITÓRIO: reprocessar relê o estado
 * atual e decide (ex.: duplicata vira {@code Duplicate}). Como {@code PreconditionFailedException}/{@code ConflictException}
 * são {@code BusinessException} — excluídas do retry —, sem esta tradução a mensagem iria direto para a DLT.
 */
public final class RetryableConflicts {

    private RetryableConflicts() {}

    public static <T> T retryOnConflict(Supplier<T> action) {
        try {
            return action.get();
        } catch (PreconditionFailedException | ConflictException e) {
            throw new IntegrationException(ErrorCodes.DEPENDENCY_FAILURE, "Conflito de concorrência; reprocessar: " + e.getMessage(), e);
        }
    }

    public static void retryOnConflict(Runnable action) {
        retryOnConflict(() -> {
            action.run();
            return null;
        });
    }
}
