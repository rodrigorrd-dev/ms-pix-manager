package br.com.pixmanager.api.messaging.payment;

import br.com.pixmanager.api.messaging.RetryableConflicts;
import br.com.pixmanager.core.port.in.payment.ApplyPaymentStatusUseCase;
import br.com.pixmanager.core.port.in.payment.ApplyPaymentStatusUseCase.SpiStatus;
import br.com.pixmanager.events.v1.SpiPaymentStatus;
import br.com.pixmanager.api.messaging.DltMetrics;
import br.com.pixmanager.api.messaging.PixKafkaListener;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.shared.util.AvroPayloadDecoder;
import io.github.springwolf.core.asyncapi.annotations.AsyncListener;
import io.github.springwolf.core.asyncapi.annotations.AsyncOperation;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.stereotype.Component;

/** Resultado do pacs.002 do pagamento. Mesma política: negócio/poison ⇒ DLT direta; técnica ⇒ retry com backoff + jitter. */
@Component
public class PaymentStatusConsumer {

    public static final String TOPIC = "pix.spi.payment-status.v1";
    public static final String CONSUMER_GROUP = "ms-pix-manager.payment-status";

    private final ApplyPaymentStatusUseCase apply;
    private final AvroPayloadDecoder decoder;
    private final DltMetrics dltMetrics;

    public PaymentStatusConsumer(ApplyPaymentStatusUseCase apply, AvroPayloadDecoder decoder, DltMetrics dltMetrics) {
        this.apply = apply;
        this.decoder = decoder;
        this.dltMetrics = dltMetrics;
    }

    @PixKafkaListener(topic = TOPIC, group = CONSUMER_GROUP)
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
        dltMetrics.onDeadLetter(record);
    }
}
