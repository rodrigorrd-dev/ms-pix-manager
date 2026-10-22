package br.com.pixmanager.api.messaging.receivedpix;

import br.com.pixmanager.api.messaging.RetryableConflicts;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.entity.ValueComponent;
import br.com.pixmanager.core.domain.enums.ComponentType;
import br.com.pixmanager.core.port.in.receivedpix.ReceivePixUseCase;
import br.com.pixmanager.core.port.in.receivedpix.ReceivePixUseCase.ReceivePixCommand;
import br.com.pixmanager.core.port.in.receivedpix.ReceivePixUseCase.ReceivePixResult;
import br.com.pixmanager.events.v1.SpiPaymentSettled;
import br.com.pixmanager.events.v1.SpiValueComponent;
import br.com.pixmanager.api.messaging.DltMetrics;
import br.com.pixmanager.shared.util.AvroPayloadDecoder;
import br.com.pixmanager.shared.exception.BusinessException;
import io.github.springwolf.bindings.kafka.annotations.KafkaAsyncOperationBinding;
import io.github.springwolf.core.asyncapi.annotations.AsyncListener;
import io.github.springwolf.core.asyncapi.annotations.AsyncOperation;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Pix recebido vindo do {@code spi-gateway} (pacs.008 liquidado). Retry/DLT (decisão 06 §6.2):
 * <ul>
 *   <li>falha de NEGÓCIO (conta desconhecida, e2eId inválido) e de DESSERIALIZAÇÃO ⇒ DLT direta, sem retry;</li>
 *   <li>falha TÉCNICA (banco, timeout) ⇒ tópicos {@code -retry-N} com backoff exponencial + jitter e, no fim, a DLT.</li>
 * </ul>
 * A mensagem repetida é absorvida pelo inbox no caso de uso (mesma transação do efeito).
 */
@Component
public class ReceivePixConsumer {

    public static final String TOPIC = "pix.spi.payment-settled.v1";
    public static final String CONSUMER_GROUP = "ms-pix-manager.receive-pix";
    private static final Logger log = LoggerFactory.getLogger(ReceivePixConsumer.class);

    private final ReceivePixUseCase receivePix;
    private final AvroPayloadDecoder decoder;
    private final DltMetrics dltMetrics;

    public ReceivePixConsumer(ReceivePixUseCase receivePix, AvroPayloadDecoder decoder, DltMetrics dltMetrics) {
        this.receivePix = receivePix;
        this.decoder = decoder;
        this.dltMetrics = dltMetrics;
    }

    @RetryableTopic(attempts = "${pix.kafka.retry.attempts:4}", numPartitions = "${pix.kafka.retry.partitions:12}",
            backoff = @Backoff(delayExpression = "${pix.kafka.retry.delay-ms:5000}", multiplierExpression = "${pix.kafka.retry.multiplier:6}",
                    maxDelayExpression = "${pix.kafka.retry.max-delay-ms:180000}", random = true),
            exclude = {BusinessException.class, DeserializationException.class},
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            retryTopicSuffix = "-retry", dltTopicSuffix = ".dlt",
            autoCreateTopics = "${pix.kafka.auto-create-retry-topics:false}")
    @KafkaListener(topics = TOPIC, groupId = CONSUMER_GROUP)
    @AsyncListener(operation = @AsyncOperation(channelName = TOPIC, description = "Pix liquidado no SPI (pacs.008) traduzido pelo spi-gateway",
            payloadType = SpiPaymentSettled.class))
    @KafkaAsyncOperationBinding
    public void onPaymentSettled(ConsumerRecord<String, byte[]> record) {
        SpiPaymentSettled spiPaymentSettled = decoder.decode(record.topic(), record.value(), SpiPaymentSettled.class);
        MDC.put("e2eId", String.valueOf(spiPaymentSettled.getEndToEndId()));        // consumer roda em thread do listener: sempre limpar
        try {
            ReceivePixResult result = RetryableConflicts.retryOnConflict(
                    () -> receivePix.receive(toCommand(record.topic(), record.partition(), record.offset(), record.headers(), spiPaymentSettled)));
            if (result instanceof ReceivePixResult.Duplicate) {
                log.info("Pix duplicado ignorado (e2e={})", spiPaymentSettled.getEndToEndId());
            }
        } finally {
            MDC.remove("e2eId");
        }
    }

    @DltHandler
    public void onDeadLetter(ConsumerRecord<String, byte[]> record) {
        dltMetrics.count(record.topic());
        // Alerta: pix_dlt_messages_total > 0. Reprocesso é manual (replay preserva o eventId; o inbox decide).
        log.error("Mensagem na DLT: topic={} partition={} offset={} key={}", record.topic(), record.partition(), record.offset(),
                record.key());
    }

    private static ReceivePixCommand toCommand(String topic, int partition, long offset, org.apache.kafka.common.header.Headers headers,
                                               SpiPaymentSettled spiPaymentSettled) {
        List<ValueComponent> components = spiPaymentSettled.getComponents().stream().map(ReceivePixConsumer::toComponent).toList();
        return new ReceivePixCommand(CONSUMER_GROUP, messageId(topic, partition, offset, headers, spiPaymentSettled), topic, partition, offset,
                spiPaymentSettled.getEndToEndId(), spiPaymentSettled.getTxid(), Money.of(spiPaymentSettled.getAmount()), spiPaymentSettled.getSettledAt(), spiPaymentSettled.getReceiverIspb(), spiPaymentSettled.getReceiverBranch(),
                spiPaymentSettled.getReceiverAccount(), spiPaymentSettled.getReceiverAccountType(), spiPaymentSettled.getPayerDocument(), spiPaymentSettled.getPayerName(), spiPaymentSettled.getPayerIspb(),
                spiPaymentSettled.getPayerBranch(), spiPaymentSettled.getPayerAccount(), spiPaymentSettled.getPayerAccountType(), spiPaymentSettled.getPayerInfo(), components, spiPaymentSettled.getReceiverKeyHash());
    }

    private static ValueComponent toComponent(SpiValueComponent spiValueComponent) {
        try {
            return new ValueComponent(ComponentType.valueOf(spiValueComponent.getType()), Money.of(spiValueComponent.getAmount()), spiValueComponent.getAgentModality(),
                    spiValueComponent.getWithdrawalProviderIspb());
        } catch (IllegalArgumentException exception) {
            // mensagem malformada nunca melhora com retry: vai direto à DLT
            throw new BusinessException(br.com.pixmanager.shared.constant.ErrorCodes.INVALID_PAYLOAD, "Componente de valor inválido no Pix recebido.", exception);
        }
    }

    /** {@code messageId} do gateway; sem ele, {@code topic-partition-offset} (estável na reentrega). */
    private static String messageId(String topic, int partition, long offset, org.apache.kafka.common.header.Headers headers,
                                    SpiPaymentSettled spiPaymentSettled) {
        if (spiPaymentSettled.getMessageId() != null && !spiPaymentSettled.getMessageId().isBlank()) {
            return spiPaymentSettled.getMessageId();
        }
        var header = headers.lastHeader("id");
        return header != null ? new String(header.value(), StandardCharsets.UTF_8)
                : topic + "-" + partition + "-" + offset;
    }
}
