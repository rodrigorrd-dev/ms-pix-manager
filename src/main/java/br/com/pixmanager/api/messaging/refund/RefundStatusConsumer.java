package br.com.pixmanager.api.messaging.refund;

import br.com.pixmanager.shared.messaging.RetryableConflicts;
import br.com.pixmanager.core.port.in.refund.SettleRefundUseCase;
import br.com.pixmanager.events.v1.SpiRefundStatus;
import br.com.pixmanager.shared.messaging.DltMetrics;
import br.com.pixmanager.shared.messaging.PixKafkaListener;
import br.com.pixmanager.shared.util.AvroPayloadDecoder;
import io.github.springwolf.core.asyncapi.annotations.AsyncListener;
import io.github.springwolf.core.asyncapi.annotations.AsyncOperation;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.stereotype.Component;

/** Resultado do pacs.004 (devolução). Mesma política de retry/DLT do recebimento: negócio ⇒ DLT direta; técnica ⇒ retry. */
@Component
public class RefundStatusConsumer {

    public static final String TOPIC = "pix.spi.refund-status.v1";
    public static final String CONSUMER_GROUP = "ms-pix-manager.refund-status";

    private final SettleRefundUseCase settle;
    private final AvroPayloadDecoder decoder;
    private final DltMetrics dltMetrics;

    public RefundStatusConsumer(SettleRefundUseCase settle, AvroPayloadDecoder decoder, DltMetrics dltMetrics) {
        this.settle = settle;
        this.decoder = decoder;
        this.dltMetrics = dltMetrics;
    }

    @PixKafkaListener(topic = TOPIC, group = CONSUMER_GROUP)
    @AsyncListener(operation = @AsyncOperation(channelName = TOPIC, description = "Resultado do pacs.004 da devolução (spi-gateway)",
            payloadType = SpiRefundStatus.class))
    public void onRefundStatus(ConsumerRecord<String, byte[]> record) {
        SpiRefundStatus spiRefundStatus = decoder.decode(record.topic(), record.value(), SpiRefundStatus.class);
        RetryableConflicts.retryOnConflict(() -> settle.apply(CONSUMER_GROUP, spiRefundStatus.getMessageId(), record.topic(), record.partition(),
                record.offset(), spiRefundStatus.getRtrId(), "SETTLED".equals(spiRefundStatus.getStatus()), spiRefundStatus.getReasonCode(), spiRefundStatus.getOccurredAt()));
    }

    @DltHandler
    public void onDeadLetter(ConsumerRecord<String, byte[]> record) {
        dltMetrics.onDeadLetter(record);
    }
}
