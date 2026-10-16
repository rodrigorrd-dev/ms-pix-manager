package br.com.pixmanager.core.domain.entity.event;

/**
 * Fato de domínio publicado via outbox. Records dos módulos implementam; o adaptador converte para Avro. O core nunca vê
 * Avro/Kafka (ArchUnit A1).
 */
public interface DomainEvent {

    /** Vira o tópico {@code pix.<aggregateType>.v1}. Ex.: {@code charge}, {@code received}, {@code payment}. */
    String aggregateType();

    /** Chave da mensagem (ordem por agregado). */
    String aggregateId();

    /** Ex.: {@code ChargeCreated}. */
    String eventType();
}
