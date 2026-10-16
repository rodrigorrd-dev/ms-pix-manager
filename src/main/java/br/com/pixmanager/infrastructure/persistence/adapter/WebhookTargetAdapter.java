package br.com.pixmanager.infrastructure.persistence.adapter;

import br.com.pixmanager.core.port.out.persistence.WebhookTargetPortOut;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class WebhookTargetAdapter implements WebhookTargetPortOut {

    private final JdbcClient jdbc;

    public WebhookTargetAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UUID> keyIdFor(String receiverKeyHash, UUID chargeId) {
        if (receiverKeyHash != null && !receiverKeyHash.isBlank()) {
            Optional<UUID> byHash = jdbc.sql("SELECT id FROM pix_key WHERE key_hash = :h AND status NOT IN ('DELETED','REJECTED')")
                    .param("h", receiverKeyHash).query(UUID.class).optional();
            if (byHash.isPresent()) {
                return byHash;
            }
        }
        if (chargeId == null) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT pix_key_id FROM pix_charge WHERE id = :c").param("c", chargeId).query(UUID.class).optional();
    }
}
