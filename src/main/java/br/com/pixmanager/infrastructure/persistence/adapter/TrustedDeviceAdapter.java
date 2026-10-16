package br.com.pixmanager.infrastructure.persistence.adapter;

import br.com.pixmanager.core.port.out.persistence.TrustedDevicePortOut;
import br.com.pixmanager.infrastructure.security.crypto.CryptoService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
public class TrustedDeviceAdapter implements TrustedDevicePortOut {

    private final JdbcClient jdbc;
    private final CryptoService crypto;

    public TrustedDeviceAdapter(JdbcClient jdbc, CryptoService crypto) {
        this.jdbc = jdbc;
        this.crypto = crypto;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isRegistered(UUID accountId, String deviceId) {
        return jdbc.sql("SELECT count(*) FROM trusted_device WHERE account_id = :a AND device_hash = :h AND status = 'ACTIVE'")
                .param("a", accountId).param("h", crypto.blindIndex("device:" + deviceId)).query(Integer.class).single() > 0;
    }
}
