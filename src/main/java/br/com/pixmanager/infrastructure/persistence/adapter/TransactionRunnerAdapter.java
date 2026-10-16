package br.com.pixmanager.infrastructure.persistence.adapter;

import br.com.pixmanager.core.port.out.persistence.TransactionRunnerPortOut;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

@Component
public class TransactionRunnerAdapter implements TransactionRunnerPortOut {

    private final TransactionTemplate template;

    public TransactionRunnerAdapter(PlatformTransactionManager manager) {
        this.template = new TransactionTemplate(manager);
    }

    @Override
    public <T> T inTransaction(Supplier<T> action) {
        return template.execute(status -> action.get());
    }
}
