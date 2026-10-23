package br.com.pixmanager.api.messaging;

import br.com.pixmanager.shared.exception.BusinessException;
import org.springframework.core.annotation.AliasFor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.retry.annotation.Backoff;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Consumer Kafka padrão do ms: o listener do tópico + a política única de retry/DLT (decisão 06 §6.2).
 * <ul>
 *   <li>falha de NEGÓCIO ({@link BusinessException}) e de DESSERIALIZAÇÃO ⇒ DLT direta, sem retry;</li>
 *   <li>falha TÉCNICA ⇒ tópicos {@code -retry-N} com backoff exponencial + jitter e, no fim, a DLT ({@code .dlt}).</li>
 * </ul>
 * Os valores vêm de {@code pix.kafka.retry.*}. Um consumer novo só informa o tópico e o grupo:
 * <pre>{@code @PixKafkaListener(topic = TOPIC, group = CONSUMER_GROUP)}</pre>
 * O método com {@code @DltHandler} continua no próprio consumer (o Spring o procura na mesma classe).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@KafkaListener
@RetryableTopic(attempts = "${pix.kafka.retry.attempts:4}", numPartitions = "${pix.kafka.retry.partitions:12}",
        backoff = @Backoff(delayExpression = "${pix.kafka.retry.delay-ms:5000}", multiplierExpression = "${pix.kafka.retry.multiplier:6}",
                maxDelayExpression = "${pix.kafka.retry.max-delay-ms:180000}", random = true),
        exclude = {BusinessException.class, DeserializationException.class},
        topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
        retryTopicSuffix = "-retry", dltTopicSuffix = ".dlt",
        autoCreateTopics = "${pix.kafka.auto-create-retry-topics:false}")
public @interface PixKafkaListener {

    /** Tópico de origem. */
    @AliasFor(annotation = KafkaListener.class, attribute = "topics")
    String topic();

    /** Consumer group. */
    @AliasFor(annotation = KafkaListener.class, attribute = "groupId")
    String group();
}
