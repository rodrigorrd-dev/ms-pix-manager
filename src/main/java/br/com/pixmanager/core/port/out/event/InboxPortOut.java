package br.com.pixmanager.core.port.out.event;

/**
 * Receptor idempotente (EIP): registra a mensagem na MESMA transação do efeito. {@code firstTime} devolve {@code false}
 * para duplicata ⇒ o consumidor não repete o efeito (BR-GEN-02). {@code messageId} = header {@code id} (eventId); para
 * tópicos de terceiros sem id, {@code <topic>-<partition>-<offset>} (estável em reentrega).
 */
public interface InboxPortOut {

    boolean firstTime(String consumerGroup, String messageId, String topic, int partition, long offset);
}
