package br.com.pixmanager.infrastructure.persistence.adapter;

import br.com.pixmanager.core.domain.entity.WebhookSubscription;
import br.com.pixmanager.infrastructure.persistence.Pages;
import br.com.pixmanager.core.domain.enums.WebhookStatus;
import br.com.pixmanager.core.port.out.persistence.WebhookSubscriptionPortOut;
import br.com.pixmanager.infrastructure.security.crypto.CryptoService;
import br.com.pixmanager.shared.model.dto.PageQuery;
import br.com.pixmanager.shared.model.dto.PageResult;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** {@code webhook_subscription} via JDBC. O hash da chave vem da própria {@code pix_key} (a assinatura é única por chave). */
@Component
public class WebhookSubscriptionPersistenceAdapter implements WebhookSubscriptionPortOut {

    private static final String COLUMNS = "s.id, s.pix_key_id, s.url, s.status, s.version, s.created_at, s.created_by";

    private final JdbcClient jdbc;
    private final CryptoService crypto;

    public WebhookSubscriptionPersistenceAdapter(JdbcClient jdbc, CryptoService crypto) {
        this.jdbc = jdbc;
        this.crypto = crypto;
    }

    @Override
    public WebhookSubscription save(WebhookSubscription webhookSubscription) {
        jdbc.sql("""
                INSERT INTO webhook_subscription (id, pix_key_id, key_hash, url, status, version, created_at, created_by, updated_at, updated_by)
                SELECT :id, k.id, k.key_hash, :url, :status, 0, :created, :by, now(), :by FROM pix_key k WHERE k.id = :key
                ON CONFLICT (id) DO UPDATE SET url = EXCLUDED.url, status = EXCLUDED.status,
                    version = webhook_subscription.version + 1, updated_at = now(), updated_by = EXCLUDED.updated_by
                """)
                .param("id", webhookSubscription.id()).param("key", webhookSubscription.pixKeyId()).param("url", webhookSubscription.url()).param("status", webhookSubscription.status().name())
                .param("created", Timestamp.from(webhookSubscription.createdAt())).param("by", webhookSubscription.createdBy()).update();
        return findByPixKeyId(webhookSubscription.pixKeyId()).orElseThrow();
    }

    @Override
    public Optional<WebhookSubscription> findByPixKeyId(UUID pixKeyId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM webhook_subscription s WHERE s.pix_key_id = :key").param("key", pixKeyId)
                .query(WebhookSubscriptionPersistenceAdapter::map).optional();
    }

    @Override
    @Transactional
    public void delete(UUID subscriptionId) {
        jdbc.sql("DELETE FROM webhook_delivery WHERE subscription_id = :id").param("id", subscriptionId).update();
        jdbc.sql("DELETE FROM webhook_subscription WHERE id = :id").param("id", subscriptionId).update();
    }

    @Override
    public PageResult<WebhookView> list(Set<UUID> accountIds, boolean allAccounts, Instant from, Instant to, PageQuery page) {
        if (!allAccounts && accountIds.isEmpty()) {
            return new PageResult<>(List.of(), page.page(), page.size(), 0);
        }
        String scope = allAccounts ? "" : " AND k.account_id IN (:accounts)";
        String where = " FROM webhook_subscription s JOIN pix_key k ON k.id = s.pix_key_id WHERE s.created_at >= :from AND s.created_at <= :to" + scope;
        var count = jdbc.sql("SELECT count(*)" + where).param("from", Timestamp.from(from)).param("to", Timestamp.from(to));
        var rows = jdbc.sql("SELECT " + COLUMNS + ", k.account_id, k.key_value_enc" + where + " ORDER BY s.created_at DESC, s.id LIMIT :n OFFSET :o")
                .param("from", Timestamp.from(from)).param("to", Timestamp.from(to)).param("n", page.size()).param("o", (long) page.page() * page.size());
        if (!allAccounts) {
            count = count.param("accounts", accountIds);
            rows = rows.param("accounts", accountIds);
        }
        final var countQuery = count;
        List<WebhookView> content = rows.query((resultSet, rowNumber) -> new WebhookView(map(resultSet, rowNumber), resultSet.getObject("account_id", UUID.class),
                crypto.decrypt(resultSet.getString("key_value_enc")))).list();
        return new PageResult<>(content, page.page(), page.size(), Pages.total(page, content, () -> countQuery.query(Long.class).single()));
    }

    private static WebhookSubscription map(ResultSet resultSet, int row) throws SQLException {
        return new WebhookSubscription(resultSet.getObject("id", UUID.class), resultSet.getObject("pix_key_id", UUID.class), resultSet.getString("url"),
                WebhookStatus.valueOf(resultSet.getString("status")), resultSet.getLong("version"), resultSet.getTimestamp("created_at").toInstant(), resultSet.getString("created_by"));
    }
}
