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

    String e2e() {
        return "E12345678202610151403" + UUID.randomUUID().toString().replace("-", "").substring(0, 11);
    }

    int rows(String e2eId) {
        return jdbc.sql("SELECT count(*) FROM pix_received WHERE end_to_end_id = :e").param("e", e2eId).query(Integer.class).single();
    }

    @Test
    @DisplayName("Mensagem válida ⇒ Pix gravado e evento na outbox; MESMA mensagem 2× ⇒ 1 efeito (inbox)")
    void shouldProcessOnceEvenWhenDelivered2Times() {
        String e2eId = e2e();
        String messageId = "msg-" + UUID.randomUUID();
        publishAvro(TOPIC, e2eId, settled(messageId, e2eId, null, "33.00", account.number()));
        publishAvro(TOPIC, e2eId, settled(messageId, e2eId, null, "33.00", account.number()));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(rows(e2eId)).isEqualTo(1));
        // dá tempo de a 2ª mensagem ser consumida e absorvida
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(6)).untilAsserted(() -> assertThat(rows(e2eId)).isEqualTo(1));
        assertThat(jdbc.sql("SELECT count(*) FROM inbox_message WHERE message_id = :m").param("m", messageId).query(Integer.class).single())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Falha de NEGÓCIO (conta desconhecida) ⇒ DLT direta, SEM passar pelos tópicos de retry")
    void businessFailureGoesStraightToDlt() {
        String e2eId = e2e();
        publishAvro(TOPIC, e2eId, settled("biz-" + UUID.randomUUID(), e2eId, null, "1.00", "000000"));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(readAll(DLT, Duration.ofSeconds(2))).anyMatch(r -> e2eId.equals(r.key())));
        assertThat(rows(e2eId)).isZero();
        assertThat(readAll(TOPIC + "-retry-0", Duration.ofSeconds(2))).noneMatch(r -> e2eId.equals(r.key()));
    }

    @Test
    @DisplayName("Poison pill (bytes que não são Avro) ⇒ DLT com os BYTES ORIGINAIS e o consumidor segue vivo")
    void poisonPillGoesToDltAndConsumerKeepsRunning() {
        byte[] garbage = "isto-nao-e-avro".getBytes();
        String badKey = "poison-" + UUID.randomUUID();
        publishRaw(TOPIC, badKey, garbage);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(readAll(DLT, Duration.ofSeconds(2))).anyMatch(r -> badKey.equals(r.key()) && java.util.Arrays.equals(garbage, r.value())));

        // depois da poison pill, uma mensagem boa ainda é processada
        String e2eId = e2e();
        publishAvro(TOPIC, e2eId, settled("after-" + UUID.randomUUID(), e2eId, null, "2.00", account.number()));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(rows(e2eId)).isEqualTo(1));
    }
}
