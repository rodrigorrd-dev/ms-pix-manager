package br.com.pixmanager.api.messaging.receivedpix;

import br.com.pixmanager.support.TestDocuments;
import br.com.pixmanager.support.integration.AbstractKafkaIT;
import br.com.pixmanager.support.integration.AccountFixtures.Account;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@DisplayName("Consumer pix.spi.payment-settled.v1 (Kafka real): sucesso, duplicata, negócio→DLT direta, poison pill→DLT")
class ReceivePixConsumerIT extends AbstractKafkaIT {

    static final String TOPIC = ReceivePixConsumer.TOPIC;
    static final String DLT = TOPIC + ".dlt";

    @Autowired JdbcClient jdbc;
    Account account;

    @BeforeEach
    void seed() {
        account = fixtures.account(TestDocuments.randomCpf(), "Recebedor");
    }

    String newEndToEndId() {
        return "E12345678202610151403" + UUID.randomUUID().toString().replace("-", "").substring(0, 11);
    }

    int rows(String endToEndId) {
        return jdbc.sql("SELECT count(*) FROM pix_received WHERE end_to_end_id = :e").param("e", endToEndId).query(Integer.class).single();
    }

    @Test
    @DisplayName("Mensagem válida ⇒ Pix gravado e evento na outbox; MESMA mensagem 2× ⇒ 1 efeito (inbox)")
    void shouldProcessOnceEvenWhenDelivered2Times() {
        String endToEndId = newEndToEndId();
        String messageId = "msg-" + UUID.randomUUID();
        publishAvro(TOPIC, endToEndId, settled(messageId, endToEndId, null, "33.00", account.number()));
        publishAvro(TOPIC, endToEndId, settled(messageId, endToEndId, null, "33.00", account.number()));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(rows(endToEndId)).isEqualTo(1));
        // dá tempo de a 2ª mensagem ser consumida e absorvida
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(6)).untilAsserted(() -> assertThat(rows(endToEndId)).isEqualTo(1));
        assertThat(jdbc.sql("SELECT count(*) FROM inbox_message WHERE message_id = :m").param("m", messageId).query(Integer.class).single())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Falha de NEGÓCIO (conta desconhecida) ⇒ DLT direta, SEM passar pelos tópicos de retry")
    void businessFailureGoesStraightToDlt() {
        String endToEndId = newEndToEndId();
        publishAvro(TOPIC, endToEndId, settled("biz-" + UUID.randomUUID(), endToEndId, null, "1.00", "000000"));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(readAll(DLT, Duration.ofSeconds(2))).anyMatch(record -> endToEndId.equals(record.key())));
        assertThat(rows(endToEndId)).isZero();
        assertThat(readAll(TOPIC + "-retry-0", Duration.ofSeconds(2))).noneMatch(record -> endToEndId.equals(record.key()));
    }

    @Test
    @DisplayName("Poison pill (bytes que não são Avro) ⇒ DLT com os BYTES ORIGINAIS e o consumidor segue vivo")
    void poisonPillGoesToDltAndConsumerKeepsRunning() {
        byte[] garbage = "isto-nao-e-avro".getBytes();
        String badKey = "poison-" + UUID.randomUUID();
        publishRaw(TOPIC, badKey, garbage);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(readAll(DLT, Duration.ofSeconds(2))).anyMatch(record -> badKey.equals(record.key()) && java.util.Arrays.equals(garbage, record.value())));

        // depois da poison pill, uma mensagem boa ainda é processada
        String endToEndId = newEndToEndId();
        publishAvro(TOPIC, endToEndId, settled("after-" + UUID.randomUUID(), endToEndId, null, "2.00", account.number()));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(rows(endToEndId)).isEqualTo(1));
    }
}
