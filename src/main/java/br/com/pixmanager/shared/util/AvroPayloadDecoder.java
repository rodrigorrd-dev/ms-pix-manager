package br.com.pixmanager.shared.util;

import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.BusinessException;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Decodifica o wire format Confluent (Avro + Schema Registry) para o {@code SpecificRecord} gerado de {@code src/main/resources/avro}.
 * Os listeners consomem {@code byte[]} de propósito: retry e DLT republicam os BYTES ORIGINAIS (nenhuma re-serialização,
 * nenhum subject extra no Schema Registry por tópico {@code -retry}/{@code .dlt}). Payload ilegível = falha de NEGÓCIO
 * (poison pill ⇒ DLT direta, sem retry; o consumidor continua vivo).
 */
@Component
public class AvroPayloadDecoder {

    private final KafkaAvroDeserializer deserializer = new KafkaAvroDeserializer();

    public AvroPayloadDecoder(@Value("${pix.kafka.schema-registry-url:mock://pix}") String schemaRegistryUrl,
                              @Value("${pix.kafka.schema-registry-user-info:}") String schemaRegistryUserInfo) {
        Map<String, Object> config = new HashMap<>();
        config.put("schema.registry.url", schemaRegistryUrl);
        if (schemaRegistryUserInfo != null && !schemaRegistryUserInfo.isBlank()) {   // registry com Basic Auth: "usuario:senha" (cofre)
            config.put("basic.auth.credentials.source", "USER_INFO");
            config.put("basic.auth.user.info", schemaRegistryUserInfo);
        }
        config.put("specific.avro.reader", true);
        config.put("avro.use.logical.type.converters", true);     // decimal/timestamp-micros/uuid
        deserializer.configure(config, false);
    }

    public <T> T decode(String topic, byte[] payload, Class<T> type) {
        try {
            Object value = deserializer.deserialize(topic, payload);
            if (!type.isInstance(value)) {
                throw new BusinessException(ErrorCodes.INVALID_PAYLOAD, "Payload inesperado em " + topic + ": esperado " + type.getSimpleName());
            }
            return type.cast(value);
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCodes.INVALID_PAYLOAD, "Payload ilegível em " + topic + " (não é Avro válido)", e);
        }
    }
}
