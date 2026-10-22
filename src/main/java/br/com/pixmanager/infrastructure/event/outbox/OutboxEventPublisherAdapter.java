package br.com.pixmanager.infrastructure.event.outbox;

import br.com.pixmanager.events.v1.EventEnvelope;
import br.com.pixmanager.shared.properties.InstitutionProperties;
import br.com.pixmanager.core.port.out.generator.IdGeneratorPortOut;
import br.com.pixmanager.infrastructure.metrics.TraceContextSupport;
import br.com.pixmanager.core.domain.entity.event.DomainEvent;
import br.com.pixmanager.core.port.out.event.DomainEventPublisherPortOut;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.apache.avro.specific.SpecificRecord;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Grava o evento Avro (wire format Confluent) em {@code outbox_event} dentro da transação do agregado (ADR-005/006).
 * O Debezium (EventRouter) lê o WAL e publica em {@code pix.<aggregate_type>.v1} com os bytes intactos.
 */
@Component
public class OutboxEventPublisherAdapter implements DomainEventPublisherPortOut {

    private final JdbcClient jdbc;
    private final List<EventAvroConverter> converters;
    private final KafkaAvroSerializer serializer = new KafkaAvroSerializer();
    private final InstitutionProperties institution;
    private final IdGeneratorPortOut ids;
    private final Clock clock;
    private final TraceContextSupport trace;

    public OutboxEventPublisherAdapter(JdbcClient jdbc, List<EventAvroConverter> converters, InstitutionProperties institution,
                                       IdGeneratorPortOut ids, Clock clock, TraceContextSupport trace,
                                       @Value("${pix.kafka.schema-registry-url:mock://pix}") String schemaRegistryUrl,
                                       @Value("${pix.kafka.auto-register-schemas:true}") boolean autoRegister,
                                       @Value("${pix.kafka.schema-registry-user-info:}") String schemaRegistryUserInfo) {
        this.jdbc = jdbc;
        this.converters = converters;
        this.institution = institution;
        this.ids = ids;
        this.clock = clock;
        this.trace = trace;
        Map<String, Object> config = new HashMap<>();
        config.put("schema.registry.url", schemaRegistryUrl);
        if (schemaRegistryUserInfo != null && !schemaRegistryUserInfo.isBlank()) {   // registry com Basic Auth: "usuario:senha" (cofre)
            config.put("basic.auth.credentials.source", "USER_INFO");
            config.put("basic.auth.user.info", schemaRegistryUserInfo);
        }
        config.put("auto.register.schemas", autoRegister);          // false em hml/prd: schemas só pelo pipeline
        config.put("use.latest.version", !autoRegister);
        config.put("avro.use.logical.type.converters", true);       // decimal/timestamp-micros/uuid
        serializer.configure(config, false);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(DomainEvent event) {
        UUID eventId = ids.newId();
        String traceParent = trace.currentTraceParent();
        EventEnvelope envelope = EventEnvelope.newBuilder()
                .setEventId(eventId)
                .setIspb(institution.ispb())
                .setEventType(event.eventType())
                .setEventVersion(1)
                .setOccurredAt(clock.instant())
                .setAggregateId(event.aggregateId())
                .setCorrelationId(MDC.get("correlationId"))
                .setTraceId(trace.currentTraceId())
                .build();
        SpecificRecord record = converters.stream()
                .filter(converter -> converter.supports(event))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Sem EventAvroConverter para " + event.getClass().getName()))
                .toAvro(event, envelope);
        String topic = "pix." + event.aggregateType() + ".v1";
        byte[] payload = serializer.serialize(topic, record);

        jdbc.sql("""
                INSERT INTO outbox_event (id, aggregate_type, aggregate_id, event_type, event_version, payload, trace_parent, created_at)
                VALUES (:id, :aggregateType, :aggregateId, :eventType, 1, :payload, :traceParent, :createdAt)
                """)
                .param("id", eventId)
                .param("aggregateType", event.aggregateType())
                .param("aggregateId", event.aggregateId())
                .param("eventType", event.eventType())
                .param("payload", payload)
                .param("traceParent", traceParent)
                .param("createdAt", java.sql.Timestamp.from(clock.instant()))
                .update();
    }
}
