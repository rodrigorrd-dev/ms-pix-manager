package br.com.pixmanager.infrastructure.persistence.adapter;

import br.com.pixmanager.core.port.out.persistence.AccountQueryPortOut;
import br.com.pixmanager.core.domain.entity.AccountInfo;
import br.com.pixmanager.core.domain.enums.AccountStatus;
import br.com.pixmanager.core.domain.enums.HolderType;
import br.com.pixmanager.shared.model.document.Document;
import br.com.pixmanager.infrastructure.security.crypto.CryptoService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** Leitura de {@code payment_account ⨝ account_holder}; o documento é decifrado só aqui, dentro do adaptador. */
@Component
public class AccountQueryAdapter implements AccountQueryPortOut {

    private final JdbcClient jdbc;
    private final CryptoService crypto;

    public AccountQueryAdapter(JdbcClient jdbc, CryptoService crypto) {
        this.jdbc = jdbc;
        this.crypto = crypto;
    }

    private static final String SELECT = """
            SELECT a.id, a.holder_id, a.ispb, a.branch, a.account_number, a.account_type, a.status AS account_status,
                   h.holder_type, h.document_enc, h.document_hash, h.name
              FROM payment_account a JOIN account_holder h ON h.id = a.holder_id
            """;

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountInfo> findById(UUID accountId) {
        return jdbc.sql(SELECT + " WHERE a.id = :id").param("id", accountId).query(this::map).optional();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountInfo> findByIdentity(String ispb, String branch, String number, String accountType) {
        return jdbc.sql(SELECT + " WHERE a.ispb = :ispb AND a.branch = :branch AND a.account_number = :number AND a.account_type = :type")
                .param("ispb", ispb).param("branch", branch).param("number", number).param("type", accountType)
                .query(this::map).optional();
    }

    private AccountInfo map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new AccountInfo(rs.getObject("id", UUID.class), rs.getObject("holder_id", UUID.class),
                "CPF".equals(rs.getString("holder_type")) ? HolderType.INDIVIDUAL : HolderType.BUSINESS,
                Document.of(crypto.decrypt(rs.getString("document_enc"))), rs.getString("document_hash"),
                rs.getString("name"), rs.getString("ispb"), rs.getString("branch"), rs.getString("account_number"),
                rs.getString("account_type"), AccountStatus.valueOf(rs.getString("account_status")));
    }
}
