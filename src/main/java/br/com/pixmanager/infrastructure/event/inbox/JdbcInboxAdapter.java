package br.com.pixmanager.infrastructure.event.inbox;

import br.com.pixmanager.core.port.out.event.InboxPortOut;

import br.com.pixmanager.core.port.out.generator.IdGeneratorPortOut;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class JdbcInboxAdapter implements InboxPortOut {

    private final JdbcClient jdbc;
    private final IdGeneratorPortOut ids;

    public JdbcInboxAdapter(JdbcClient jdbc, IdGeneratorPortOut ids) {
        this.jdbc = jdbc;
        this.ids = ids;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean firstTime(String consumerGroup, String messageId, String topic, int partition, long offset) {
        int inserted = jdbc.sql("""
                INSERT INTO inbox_message (id, consumer_group, message_id, topic, kafka_partition, kafka_offset)
                VALUES (:id, :group, :messageId, :topic, :partition, :offset)
                ON CONFLICT (consumer_group, message_id) DO NOTHING
                """)
                .param("id", ids.newId())
                .param("group", consumerGroup)
                .param("messageId", messageId)
                .param("topic", topic)
                .param("partition", partition)
                .param("offset", offset)
                .update();
        return inserted == 1;
    }
}
