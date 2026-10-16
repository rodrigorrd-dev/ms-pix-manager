package br.com.pixmanager.core.port.out.persistence;

import java.util.function.Supplier;

/**
 * Porta de transação para casos de uso que ORQUESTRAM chamada externa (HTTP) e precisam de uma janela transacional só
 * para o passo final (agregado + outbox). Nunca chamar HTTP dentro do {@code Supplier}.
 */
public interface TransactionRunnerPortOut {

    <T> T inTransaction(Supplier<T> action);

    default void inTransaction(Runnable action) {
        inTransaction(() -> {
            action.run();
            return null;
        });
    }
}
