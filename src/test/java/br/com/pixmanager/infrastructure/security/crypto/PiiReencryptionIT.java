package br.com.pixmanager.infrastructure.security.crypto;

import br.com.pixmanager.shared.properties.CryptoProperties;
import br.com.pixmanager.support.TestDocuments;
import br.com.pixmanager.support.integration.AbstractApplicationIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Rotação de chave AES: re-criptografia em lote das colunas *_enc")
class PiiReencryptionIT extends AbstractApplicationIT {

    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager tx;
    @Autowired CryptoService atual;        // v1 (chave de teste)

    static final String AES_V2 = Base64.getEncoder().encodeToString("abcdefghijklmnopqrstuvwxyz012345".getBytes());
    static final String AES_V1 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";           // = pix.crypto.aes-key-base64 de application-test.yml
    static final String HMAC = "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8=";

    @Test
    @DisplayName("linhas v1 viram v2 (decifram igual), lote a lote; segunda execução não faz nada; chave antiga ausente é reportada sem laço infinito")
    void shouldMoveEveryRowToTheNewVersion() {
        String cpf1 = TestDocuments.randomCpf(), cpf2 = TestDocuments.randomCpf(), cpf3 = TestDocuments.randomCpf();
        UUID h1 = holder(cpf1), h2 = holder(cpf2), h3 = holder(cpf3);
        assertThat(versionOf(h1)).isEqualTo("v1");

        CryptoService v2 = new CryptoService(new CryptoProperties(AES_V2, HMAC, "v2", Map.of("v1", AES_V1), new CryptoProperties.Reencrypt(true, 2)));
        PiiReencryptionRunner runner = new PiiReencryptionRunner(jdbc, tx, v2, new CryptoProperties(AES_V2, HMAC, "v2", Map.of("v1", AES_V1), new CryptoProperties.Reencrypt(true, 2)));

        Map<String, Integer> first = runner.reencryptAll();
        assertThat(first.get("account_holder.document_enc")).isGreaterThanOrEqualTo(3);     // lote de 2 ⇒ foram necessários vários lotes
        for (UUID id : new UUID[]{h1, h2, h3}) {
            assertThat(versionOf(id)).isEqualTo("v2");
        }
        assertThat(v2.decrypt(valueOf(h1))).isEqualTo(cpf1);
        assertThat(v2.decrypt(valueOf(h3))).isEqualTo(cpf3);
        assertThat(runner.reencryptAll().get("account_holder.document_enc")).isZero();       // idempotente

        // dado de uma versão sem chave no chaveiro: reportado como falha, sem girar em falso
        UUID orfao = holder(cpf1 + "x", "v7:" + Base64.getEncoder().encodeToString(new byte[40]));
        try {
            Map<String, Integer> again = runner.reencryptAll();
            assertThat(again.get("account_holder.document_enc.falhas")).isEqualTo(1);
            assertThat(versionOf(orfao)).isEqualTo("v7");
        } finally {
            jdbc.sql("DELETE FROM account_holder WHERE id = :id").param("id", orfao).update();
            voltarParaV1();
        }
    }

    /** O banco do teste é compartilhado com outros ITs, que só conhecem a chave v1: devolve tudo para v1 (também prova a rotação no sentido inverso). */
    private void voltarParaV1() {
        CryptoProperties props = new CryptoProperties(AES_V1, HMAC, "v1", Map.of("v2", AES_V2), new CryptoProperties.Reencrypt(true, 100));
        new PiiReencryptionRunner(jdbc, tx, new CryptoService(props), props).reencryptAll();
    }

    private UUID holder(String document) {
        return holder(document, atual.encrypt(document));
    }

    private UUID holder(String document, String enc) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO account_holder (id, holder_type, document_enc, document_hash, document_masked, name, status, created_by, updated_by)
                VALUES (:id, 'CPF', :enc, :hash, 'masked', 'Rotacao', 'ACTIVE', 'test', 'test')
                """).param("id", id).param("enc", enc).param("hash", atual.blindIndex(document + id)).update();
        return id;
    }

    private String valueOf(UUID id) {
        return jdbc.sql("SELECT document_enc FROM account_holder WHERE id = :id").param("id", id).query(String.class).single();
    }

    private String versionOf(UUID id) {
        String v = valueOf(id);
        return v.substring(0, v.indexOf(':'));
    }
}
