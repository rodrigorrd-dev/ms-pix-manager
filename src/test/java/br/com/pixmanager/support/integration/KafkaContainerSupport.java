package br.com.pixmanager.support.integration;

import org.testcontainers.kafka.ConfluentKafkaContainer;

/** Kafka KRaft SINGLETON por JVM (mesma imagem do compose). O Schema Registry é em memória ({@code mock://}) — o pipeline real usa o registry de verdade. */
public final class KafkaContainerSupport {

    public static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.6.1");

    static {
        KAFKA.start();
    }

    private KafkaContainerSupport() {}
}
