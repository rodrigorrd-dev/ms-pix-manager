package br.com.pixmanager.api.messaging.refund;

import br.com.pixmanager.api.messaging.RetryableConflicts;
import br.com.pixmanager.core.port.in.refund.SettleRefundUseCase;
import br.com.pixmanager.events.v1.SpiRefundStatus;
import br.com.pixmanager.api.messaging.DltMetrics;
import br.com.pixmanager.shared.util.AvroPayloadDecoder;
import br.com.pixmanager.shared.exception.BusinessException;
import io.github.springwolf.core.asyncapi.annotations.AsyncListener;
import io.github.springwolf.core.asyncapi.annotations.AsyncOperation;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

/** Resultado do pacs.004 (devolução). Mesma política de retry/DLT do recebimento: negócio ⇒ DLT direta; técnica ⇒ retry. */
@Component
public class RefundStatusConsumer {

    public static final String TOPIC = "pix.spi.refund-status.v1";
    public static final String CONSUMER_GROUP = "ms-pix-manager.refund-status";
    private static final Logger log = LoggerFactory.getLogger(RefundStatusConsumer.class);

    private final SettleRefundUseCase settle;
    private final AvroPayloadDecoder decoder;
    private final DltMetrics dltMetrics;

    public RefundStatusConsumer(SettleRefundUseCase settle, AvroPayloadDecoder decoder, DltMetrics dltMetrics) {
        this.settle = settle;
        this.decoder = decoder;
        this.dltMetrics = dltMetrics;
    }

    @RetryableTopic(attempts = "${pix.kafka.retry.attempts:4}", numPartitions = "${pix.kafka.retry.partitions:12}",
            backoff = @Backoff(delayExpression = "${pix.kafka.retry.delay-ms:5000}", multiplierExpression = "${pix.kafka.retry.multiplier:6}",
                    maxDelayExpression = "${pix.kafka.retry.max-delay-ms:180000}", random = true),
            exclude = {BusinessException.class, DeserializationException.class},
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE, retryTopicSuffix = "-retry", dltTopicSuffix = ".dlt",
            autoCreateTopics = "${pix.kafka.auto-create-retry-topics:false}")
    @KafkaListener(topics = TOPIC, groupId = CONSUMER_GROUP)
    @AsyncListener(operation = @AsyncOperation(channelName = TOPIC, description = "Resultado do pacs.004 da devolução (spi-gateway)",
            payloadType = SpiRefundStatus.class))
    public void onRefundStatus(ConsumerRecord<String, byte[]> record) {
        SpiRefundStatus m = decoder.decode(record.topic(), record.value(), SpiRefundStatus.class);
        RetryableConflicts.retryOnConflict(() -> settle.apply(CONSUMER_GROUP, m.getMessageId(), record.topic(), record.partition(),
                record.offset(), m.getRtrId(), "SETTLED".equals(m.getStatus()), m.getReasonCode(), m.getOccurredAt()));
    }

    @DltHandler
    public void onDeadLetter(ConsumerRecord<String, byte[]> record) {
        dltMetrics.count(record.topic());
        log.error("Mensagem na DLT: topic={} partition={} offset={} key={}", record.topic(), record.partition(), record.offset(),
                record.key());
    }
}
