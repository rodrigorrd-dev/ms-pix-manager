package br.com.pixmanager.support.integration;

import br.com.pixmanager.infrastructure.security.crypto.CryptoService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Dados de teste gravados direto no banco (o ms não cadastra titular/conta: a fonte é o core — decisão A2). */
@Component
public class AccountFixtures {

    private static final AtomicLong ACCOUNT_SEQ = new AtomicLong(100000);

    private final JdbcClient jdbc;
    private final CryptoService crypto;

    public AccountFixtures(JdbcClient jdbc, CryptoService crypto) {
        this.jdbc = jdbc;
        this.crypto = crypto;
    }

    public record Account(UUID id, UUID holderId, String documentHash, String number) {}

    /** Titular + conta ativa. {@code document}: CPF (PF) ou CNPJ (PJ) VÁLIDO, sem máscara. */
    public Account account(String document, String name) {
        UUID holderId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        String hash = crypto.blindIndex(document);
        String number = String.valueOf(ACCOUNT_SEQ.incrementAndGet());
        jdbc.sql("""
                INSERT INTO account_holder (id, holder_type, document_enc, document_hash, document_masked, name, status, created_by, updated_by)
                VALUES (:id, :type, :enc, :hash, :masked, :name, 'ACTIVE', 'test', 'test')
                """)
                .param("id", holderId).param("type", document.length() == 11 ? "CPF" : "CNPJ")
                .param("enc", crypto.encrypt(document)).param("hash", hash).param("masked", "masked").param("name", name).update();
        jdbc.sql("""
                INSERT INTO payment_account (id, holder_id, ispb, branch, account_number, account_type, status, opened_at, created_by, updated_by)
                VALUES (:id, :holder, '12345678', '0001', :number, 'CACC', 'ACTIVE', :opened, 'test', 'test')
                """)
                .param("id", accountId).param("holder", holderId).param("number", number)
                .param("opened", java.sql.Timestamp.from(Instant.parse("2020-01-01T00:00:00Z"))).update();
        return new Account(accountId, holderId, hash, number);
    }

    /** Chave ATIVA já registrada no DICT (pula o ciclo PENDING_DICT). */
    public UUID activeKey(UUID accountId, String type, String value) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO pix_key (id, account_id, key_type, key_value_enc, key_hash, key_masked, status, dict_cid, dict_registered_at,
                                     created_by, updated_by)
                VALUES (:id, :account, :type, :enc, :hash, 'masked', 'ACTIVE', 'cid-test', now(), 'test', 'test')
                """)
                .param("id", id).param("account", accountId).param("type", type).param("enc", crypto.encrypt(value))
                .param("hash", crypto.blindIndex("key:" + value)).update();
        return id;
    }

    public int outboxCount(String eventType) {
        return jdbc.sql("SELECT count(*) FROM outbox_event WHERE event_type = :t").param("t", eventType).query(Integer.class).single();
    }

    /** Dispositivo cadastrado (só o índice cego é gravado, como no adaptador de produção). */
    public void trustedDevice(UUID accountId, String deviceId) {
        jdbc.sql("""
                INSERT INTO trusted_device (id, account_id, device_hash, status, registered_at)
                VALUES (:id, :a, :h, 'ACTIVE', now())
                """).param("id", UUID.randomUUID()).param("a", accountId).param("h", crypto.blindIndex("device:" + deviceId)).update();
    }

    public java.math.BigDecimal dailyUsage(UUID accountId, String scope) {
        return jdbc.sql("SELECT coalesce(sum(used_amount), 0) FROM limit_usage WHERE account_id = :a AND scope = :s")
                .param("a", accountId).param("s", scope).query(java.math.BigDecimal.class).single();
    }
}
