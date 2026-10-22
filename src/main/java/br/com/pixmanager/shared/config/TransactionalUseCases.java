package br.com.pixmanager.shared.config;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

/**
 * Fronteira transacional POR CASO DE USO sem anotar o core: envolve o {@code *UseCase} num proxy que abre uma transação
 * ({@link TransactionTemplate}) em volta de cada chamada. Resolve o defeito registrado em jpa-patterns ("nenhuma
 * transação cobre o caso de uso"): agregado + outbox + inbox são atômicos. Nunca chamar HTTP dentro do use case.
 */
public final class TransactionalUseCases {

    private TransactionalUseCases() {}

    @SuppressWarnings("unchecked")
    public static <T> T wrap(Class<T> useCase, T target, PlatformTransactionManager txManager) {
        TransactionTemplate template = new TransactionTemplate(txManager);
        return (T) Proxy.newProxyInstance(useCase.getClassLoader(), new Class<?>[]{useCase}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(target, args);
            }
            try {
                return template.execute(status -> {
                    try {
                        return method.invoke(target, args);
                    } catch (InvocationTargetException exception) {
                        throw sneaky(exception.getCause());
                    } catch (IllegalAccessException exception) {
                        throw new IllegalStateException(exception);
                    }
                });
            } catch (UndeclaredThrowableWrapper undeclaredThrowableWrapper) {
                throw undeclaredThrowableWrapper.getCause();
            }
        });
    }

    private static RuntimeException sneaky(Throwable cause) {
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new UndeclaredThrowableWrapper(cause);
    }

    private static final class UndeclaredThrowableWrapper extends RuntimeException {
        UndeclaredThrowableWrapper(Throwable cause) {
            super(cause);
        }
    }
}
