package br.com.pixmanager.infrastructure.security.crypto;

import br.com.pixmanager.shared.exception.CryptoException;
import br.com.pixmanager.shared.properties.CryptoProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rotação da chave AES: re-criptografa, em lotes, toda coluna {@code *_enc} que ainda não está na versão ATUAL.
 *
 * <p>Procedimento (docs/runbooks/rotacao-de-chave.md): 1) gere a chave nova; 2) suba UMA instância com
 * {@code PIX_CRYPTO_AES_KEY=<nova>}, {@code PIX_CRYPTO_KEY_VERSION=v2}, {@code PIX_CRYPTO_PREVIOUS_AES_KEYS_V1=<antiga>} e
 * {@code PIX_CRYPTO_REENCRYPT=true}; 3) espere o log "re-criptografia concluída"; 4) desligue a flag e só então remova a chave antiga.
 * Cada lote é uma transação; a atualização é condicionada ao valor antigo ({@code WHERE col = :old}), então é segura com tráfego
 * concorrente e pode ser repetida. NÃO cobre o índice cego (HMAC), que não tem rotação.
 */
@Component
@ConditionalOnProperty(prefix = "pix.crypto.reencrypt", name = "enabled", havingValue = "true")
public class PiiReencryptionRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PiiReencryptionRunner.class);

    /** Toda coluna cifrada do schema (confira com: SELECT table_name, column_name FROM information_schema.columns WHERE column_name LIKE '%\_enc'). */
    static final List<Target> TARGETS = List.of(
            new Target("account_holder", "document_enc"), new Target("pix_key", "key_value_enc"),
            new Target("pix_charge", "debtor_document_enc"), new Target("pix_received", "payer_document_enc"),
            new Target("payment_order", "dest_document_enc"), new Target("scheduled_payment", "dest_document_enc"), new Target("scheduled_payment", "destination_key_enc"),
            new Target("pix_automatic_authorization", "receiver_document_enc"));

    record Target(String table, String column) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate transactionTemplate;
    private final CryptoService crypto;
    private final int batchSize;

    public PiiReencryptionRunner(JdbcClient jdbc, PlatformTransactionManager transactionManager, CryptoService crypto, CryptoProperties properties) {
        this.jdbc = jdbc;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.crypto = crypto;
        this.batchSize = properties.reencrypt().batchSize();
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<String, Integer> result = reencryptAll();
        log.info("re-criptografia concluída para a versão {}: {}", crypto.currentVersion(), result);
    }

    /** @return linhas atualizadas por {@code tabela.coluna} (falhas de decifragem são logadas e contadas como {@code .falhas}). */
    public Map<String, Integer> reencryptAll() {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Target target : TARGETS) {
            int updated = 0;
            int failed = 0;
            while (true) {
                int[] batch = transactionTemplate.execute(status -> reencryptBatch(target));
                updated += batch[0];
                failed += batch[1];
                if (batch[0] == 0) {
                    break;                       // nada mais a migrar (ou só restaram linhas ilegíveis: não girar em falso)
                }
            }
            result.put(target.table() + "." + target.column(), updated);
            if (failed > 0) {
                result.put(target.table() + "." + target.column() + ".falhas", failed);
                log.error("{}.{}: {} linha(s) não puderam ser decifradas (chave antiga ausente em pix.crypto.previous-aes-keys?)", target.table(), target.column(), failed);
            }
        }
        return result;
    }

    private int[] reencryptBatch(Target target) {
        String select = "SELECT id, %1$s AS value FROM %2$s WHERE %1$s IS NOT NULL AND %1$s NOT LIKE :current ORDER BY id LIMIT :n"
                .formatted(target.column(), target.table());
        String update = "UPDATE %2$s SET %1$s = :new WHERE id = :id AND %1$s = :old".formatted(target.column(), target.table());
        var rows = jdbc.sql(select).param("current", crypto.currentVersion() + ":%").param("n", batchSize)
                .query((resultSet, rowNumber) -> new Object[]{resultSet.getObject("id"), resultSet.getString("value")}).list();
        int updated = 0;
        int failed = 0;
        for (Object[] row : rows) {
            try {
                String fresh = crypto.encrypt(crypto.decrypt((String) row[1]));
                updated += jdbc.sql(update).param("new", fresh).param("id", row[0]).param("old", row[1]).update();
            } catch (CryptoException exception) {
                failed++;
                log.error("{}.{} id={}: {}", target.table(), target.column(), row[0], exception.getMessage());
            }
        }
        return new int[]{updated, failed};
    }
}
