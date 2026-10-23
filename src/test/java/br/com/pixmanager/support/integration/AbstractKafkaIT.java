package br.com.pixmanager.support.integration;

import br.com.pixmanager.events.v1.SpiPaymentSettled;
import br.com.pixmanager.events.v1.SpiValueComponent;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Base dos ITs de mensageria: Postgres + Kafka reais, listeners LIGADOS, registry em memória compartilhado com o decoder da app.
 * {@code AFTER_CLASS}: o contexto é ENCERRADO ao fim de cada classe — dois contextos vivos dividiriam os mesmos consumer groups
 * e um deles "roubaria" mensagens do outro (isolamento de teste, não comportamento da aplicação).
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractKafkaIT extends AbstractApplicationIT {

    protected static final String REGISTRY = "mock://pix-kafka-it";

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KafkaContainerSupport.KAFKA::getBootstrapServers);
        registry.add("spring.kafka.listener.auto-startup", () -> "true");
        registry.add("pix.kafka.schema-registry-url", () -> REGISTRY);
    }

    protected static String bootstrap() {
        return KafkaContainerSupport.KAFKA.getBootstrapServers();
    }

    /** Publica um Avro com o MESMO serializer/registry do spi-gateway (wire format Confluent). */
    protected static void publishAvro(String topic, String key, Object record) {
        Map<String, Object> props = Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class,
                "schema.registry.url", REGISTRY, "auto.register.schemas", true, "avro.use.logical.type.converters", true);
        try (KafkaProducer<String, Object> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(topic, key, record));
            producer.flush();
        }
    }

    protected static void publishRaw(String topic, String key, byte[] bytes) {
        Map<String, Object> props = Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(topic, key, bytes));
            producer.flush();
        }
    }

    /** Lê TODOS os registros do tópico (do início) até {@code wait}. */
    protected static List<ConsumerRecord<String, byte[]>> readAll(String topic, Duration wait) {
        Map<String, Object> props = Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap(),
                ConsumerConfig.GROUP_ID_CONFIG, "reader-" + UUID.randomUUID(), ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        List<ConsumerRecord<String, byte[]>> out = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + wait.toNanos();
            while (System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(300)).forEach(out::add);
            }
        }
        return out;
    }

    protected static SpiPaymentSettled settled(String messageId, String endToEndId, String txid, String amount, String receiverAccount) {
        return SpiPaymentSettled.newBuilder()
                .setMessageId(messageId).setEndToEndId(endToEndId).setTxid(txid).setAmount(new BigDecimal(amount)).setSettledAt(Instant.now())
                .setReceiverIspb("12345678").setReceiverBranch("0001").setReceiverAccount(receiverAccount).setReceiverAccountType("CACC")
                .setReceiverKeyHash(null).setPayerDocumentType("CPF").setPayerDocument("11144477735").setPayerName("Maria Pagadora")
                .setPayerIspb("87654321").setPayerBranch("0002").setPayerAccount("99887").setPayerAccountType("CACC").setPayerInfo("teste")
                .setComponents(List.of(SpiValueComponent.newBuilder().setType("ORIGINAL").setAmount(new BigDecimal(amount))
                        .setAgentModality(null).setWithdrawalProviderIspb(null).build()))
                .build();
    }
}
