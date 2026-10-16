package br.com.pixmanager.core.port.out.event;

import br.com.pixmanager.core.domain.entity.event.DomainEvent;

/**
 * Porta de saída de eventos. A implementação grava na {@code outbox_event} <b>na transação corrente</b> (obrigatório —
 * {@code Propagation.MANDATORY}); o Debezium publica no Kafka. Falha de serialização/schema ⇒ exceção antes do commit.
 */
public interface DomainEventPublisherPortOut {

    void publish(DomainEvent event);
}
