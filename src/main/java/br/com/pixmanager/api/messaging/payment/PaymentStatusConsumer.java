package br.com.pixmanager.api.messaging.payment;

import br.com.pixmanager.api.messaging.RetryableConflicts;
import br.com.pixmanager.core.port.in.payment.ApplyPaymentStatusUseCase;
import br.com.pixmanager.core.port.in.payment.ApplyPaymentStatusUseCase.SpiStatus;
import br.com.pixmanager.events.v1.SpiPaymentStatus;
import br.com.pixmanager.api.messaging.DltMetrics;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.shared.util.AvroPayloadDecoder;
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

/** Resultado do pacs.002 do pagamento. Mesma política: negócio/poison ⇒ DLT direta; técnica ⇒ retry com backoff + jitter. */
@Component
public class PaymentStatusConsumer {

    public static final String TOPIC = "pix.spi.payment-status.v1";
    public static final String CONSUMER_GROUP = "ms-pix-manager.payment-status";
    private static final Logger log = LoggerFactory.getLogger(PaymentStatusConsumer.class);

    private final ApplyPaymentStatusUseCase apply;
    private final AvroPayloadDecoder decoder;
    private final DltMetrics dltMetrics;

    public PaymentStatusConsumer(ApplyPaymentStatusUseCase apply, AvroPayloadDecoder decoder, DltMetrics dltMetrics) {
        this.apply = apply;
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
    @AsyncListener(operation = @AsyncOperation(channelName = TOPIC, description = "Status do pagamento no SPI (pacs.002) via spi-gateway",
            payloadType = SpiPaymentStatus.class))
    public void onPaymentStatus(ConsumerRecord<String, byte[]> record) {
        SpiPaymentStatus spiPaymentStatus = decoder.decode(record.topic(), record.value(), SpiPaymentStatus.class);
        SpiStatus status;
        try {
            status = SpiStatus.valueOf(spiPaymentStatus.getStatus());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCodes.INVALID_PAYLOAD, "Status de pagamento desconhecido: " + spiPaymentStatus.getStatus());
        }
        RetryableConflicts.retryOnConflict(() -> apply.apply(CONSUMER_GROUP, spiPaymentStatus.getMessageId(), record.topic(), record.partition(),
                record.offset(), spiPaymentStatus.getEndToEndId(), status, spiPaymentStatus.getReasonCode(), spiPaymentStatus.getOccurredAt()));
    }

    @DltHandler
    public void onDeadLetter(ConsumerRecord<String, byte[]> record) {
        dltMetrics.count(record.topic());
        log.error("Mensagem na DLT: topic={} partition={} offset={} key={}", record.topic(), record.partition(), record.offset(), record.key());
    }
}
