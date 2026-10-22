package br.com.pixmanager.infrastructure.event.outbox;

import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline REAL da outbox: Postgres (wal_level=logical, changelog Liquibase, publication) → Debezium Connect com o
 * {@code docker/debezium/outbox-connector.json} de verdade (EventRouter) → Kafka. Prova roteamento por {@code aggregate_type},
 * chave por {@code aggregate_id}, payload em bytes INTACTO (Avro) e headers.
 */
@DisplayName("Outbox → Debezium EventRouter → Kafka (conector real do docker/)")
class DebeziumOutboxPipelineIT {

    static final Network NET = Network.newNetwork();
    static PostgreSQLContainer<?> postgres;
    static ConfluentKafkaContainer kafka;
    static GenericContainer<?> connect;

    @BeforeAll
    static void start() throws Exception {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("pixmanager").withUsername("pix").withPassword("pix_local_only")
                .withCommand("postgres", "-c", "wal_level=logical").withNetwork(NET).withNetworkAliases("postgres");
        kafka = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.6.1").withNetwork(NET).withNetworkAliases("kafka");
        postgres.start();
        kafka.start();
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Liquibase liquibase = new Liquibase("db/changelog/db.changelog-master.yaml", new ClassLoaderResourceAccessor(),
                     DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            liquibase.update("");
        }
        connect = new GenericContainer<>("quay.io/debezium/connect:3.0").withNetwork(NET).withExposedPorts(8083)
                .withEnv(Map.of("BOOTSTRAP_SERVERS", "kafka:9093", "GROUP_ID", "it-connect", "CONFIG_STORAGE_TOPIC", "_cfg",
                        "OFFSET_STORAGE_TOPIC", "_off", "STATUS_STORAGE_TOPIC", "_sts", "CONFIG_STORAGE_REPLICATION_FACTOR", "1",
                        "OFFSET_STORAGE_REPLICATION_FACTOR", "1", "STATUS_STORAGE_REPLICATION_FACTOR", "1",
                        "KEY_CONVERTER", "org.apache.kafka.connect.storage.StringConverter",
                        "VALUE_CONVERTER", "org.apache.kafka.connect.converters.ByteArrayConverter"))
                .waitingFor(Wait.forHttp("/connectors").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)));
        connect.start();
        register();
    }

    @AfterAll
    static void stop() {
        for (var c : new org.testcontainers.lifecycle.Startable[]{connect, kafka, postgres}) {
            if (c != null) {
                c.stop();
            }
        }
        NET.close();
    }

    /** Registra o conector DO REPOSITÓRIO (só a senha de teste é ajustada, não a estrutura). */
    static void register() throws Exception {
        String json = Files.readString(Path.of("docker/debezium/outbox-connector.json"));
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://" + connect.getHost() + ":" + connect.getMappedPort(8083) + "/connectors"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("registro do conector: " + response.body()).isIn(200, 201);
    }

    @Test
    @DisplayName("Linha na outbox vira mensagem em pix.<aggregate_type>.v1 com chave, bytes Avro intactos e headers")
    void shouldRouteOutboxRowsToKafka() throws Exception {
        awaitActiveSlot();          // snapshot.mode=no_data: linhas anteriores ao slot não são capturadas (decisão deliberada)
        UUID eventId = UUID.randomUUID();
        String aggregateId = UUID.randomUUID().toString();
        byte[] payload = {0x00, 0x00, 0x00, 0x00, 0x07, (byte) 0xFF, 0x10, 0x20, (byte) 0x80, 0x7F};   // wire format Confluent, inclui bytes não-UTF8
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO outbox_event (id, aggregate_type, aggregate_id, event_type, event_version, payload, trace_parent)
                     VALUES (?, 'payment', ?, 'PaymentOrderCreated', 1, ?, '00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01')
                     """)) {
            statement.setObject(1, eventId);
            statement.setString(2, aggregateId);
            statement.setBytes(3, payload);
            statement.executeUpdate();
        }

        ConsumerRecord<String, byte[]> got = null;
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(), ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest", ConsumerConfig.METADATA_MAX_AGE_CONFIG, 1000,   // assinatura por regex só vê tópico novo após o refresh
                 ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class))) {
            consumer.subscribe(java.util.regex.Pattern.compile("pix\\..*\\.v1"));
            long deadline = System.currentTimeMillis() + 90_000;
            while (got == null && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, byte[]> rec : consumer.poll(Duration.ofSeconds(2))) {
                    if (aggregateId.equals(rec.key())) {
                        got = rec;
                    }
                }
            }
        }
        assertThat(got).as("mensagem roteada pelo Debezium\n" + diagnostics()).isNotNull();
        assertThat(got.topic()).isEqualTo("pix.payment.v1");
        assertThat(got.value()).as("bytes Avro intactos (ByteArrayConverter)").isEqualTo(payload);
        assertThat(headers(got)).containsEntry("eventType", "PaymentOrderCreated").containsEntry("eventVersion", "1")
                .containsKey("traceparent").containsKey("id");
    }

    private static void awaitActiveSlot() throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            while (System.currentTimeMillis() < deadline) {
                try (var st = connection.createStatement(); var rs = st.executeQuery(
                        "SELECT count(*) FROM pg_replication_slots WHERE slot_name = 'pix_outbox_slot' AND active")) {
                    rs.next();
                    if (rs.getInt(1) == 1) {
                        return;
                    }
                }
                Thread.sleep(500);
            }
        }
        throw new AssertionError("slot pix_outbox_slot não ficou ativo");
    }

    private static String diagnostics() throws Exception {
        String status = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://" + connect.getHost() + ":"
                + connect.getMappedPort(8083) + "/connectors/pix-outbox-connector/status")).build(), HttpResponse.BodyHandlers.ofString()).body();
        String logs = connect.getLogs();
        String errs = String.join("\n", logs.lines().filter(logLine -> logLine.contains("ERROR") || logLine.contains("Caused by") || logLine.contains("WARN")).limit(15).toList());
        String topics;
        try (var admin = org.apache.kafka.clients.admin.AdminClient.create(Map.<String, Object>of("bootstrap.servers", kafka.getBootstrapServers()))) {
            topics = admin.listTopics().names().get().toString();
        }
        String stream = String.join("\n", logs.lines().filter(logLine -> logLine.contains("io.debezium") && !logLine.contains("TypeRegistry") && !logLine.contains("Added ")).skip(0).limit(40).toList());
        return "status=" + status + "\ntopics=" + topics + "\n" + stream + "\n" + errs;
    }

    private static Map<String, String> headers(ConsumerRecord<String, byte[]> rec) {
        var map = new java.util.HashMap<String, String>();
        rec.headers().forEach(header -> map.put(header.key(), new String(header.value())));
        return map;
    }
}
