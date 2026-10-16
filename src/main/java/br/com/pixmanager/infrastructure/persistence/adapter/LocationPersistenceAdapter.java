package br.com.pixmanager.infrastructure.persistence.adapter;

import br.com.pixmanager.core.port.out.persistence.LocationPortOut;
import br.com.pixmanager.core.domain.enums.ChargeType;
import br.com.pixmanager.core.domain.entity.PixLocation;
import br.com.pixmanager.core.port.out.generator.IdGeneratorPortOut;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

@Component
public class LocationPersistenceAdapter implements LocationPortOut {

    private final JdbcClient jdbc;
    private final IdGeneratorPortOut ids;
    private final SecureRandom random = new SecureRandom();

    public LocationPersistenceAdapter(JdbcClient jdbc, IdGeneratorPortOut ids) {
        this.jdbc = jdbc;
        this.ids = ids;
    }

    @Override
    @Transactional
    public PixLocation create(ChargeType type) {
        UUID id = ids.newId();
        long locationId = jdbc.sql("SELECT nextval('seq_pix_location_id')").query(Long.class).single();
        byte[] token = new byte[24];
        random.nextBytes(token);
        String urlPath = Base64.getUrlEncoder().withoutPadding().encodeToString(token);   // 32 chars, não adivinhável
        jdbc.sql("""
                INSERT INTO pix_location (id, location_id, location_type, url_path, created_at)
                VALUES (:id, :locationId, :type, :urlPath, now())
                """)
                .param("id", id).param("locationId", locationId).param("type", type == ChargeType.IMMEDIATE ? "COB" : "COBV")
                .param("urlPath", urlPath).update();
        return new PixLocation(id, locationId, type, urlPath);
    }
}
