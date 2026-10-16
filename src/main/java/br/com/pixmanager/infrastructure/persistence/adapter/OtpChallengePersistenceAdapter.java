package br.com.pixmanager.infrastructure.persistence.adapter;

import br.com.pixmanager.core.domain.entity.OtpChallenge;
import br.com.pixmanager.core.domain.enums.OtpChannel;
import br.com.pixmanager.core.port.out.persistence.OtpChallengePortOut;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Tabela {@code otp_challenge} via JDBC (sem entidade JPA: é um registro simples, de vida curta). Participa da transação corrente. */
@Component
public class OtpChallengePersistenceAdapter implements OtpChallengePortOut {

    private static final String COLUMNS = "id, pix_key_id, channel, code_hash, attempts, expires_at, verified_at, created_at";

    private final JdbcClient jdbc;

    public OtpChallengePersistenceAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public OtpChallenge save(OtpChallenge c) {
        jdbc.sql("""
                INSERT INTO otp_challenge (id, pix_key_id, channel, code_hash, attempts, expires_at, verified_at, created_at)
                VALUES (:id, :key, :channel, :hash, :attempts, :expires, :verified, :created)
                ON CONFLICT (id) DO UPDATE SET attempts = EXCLUDED.attempts, verified_at = EXCLUDED.verified_at
                """)
                .param("id", c.id()).param("key", c.pixKeyId()).param("channel", c.channel().name()).param("hash", c.codeHash())
                .param("attempts", c.attempts()).param("expires", Timestamp.from(c.expiresAt()))
                .param("verified", c.verifiedAt() == null ? null : Timestamp.from(c.verifiedAt())).param("created", Timestamp.from(c.createdAt()))
                .update();
        return c;
    }

    @Override
    public Optional<OtpChallenge> findLatest(UUID pixKeyId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM otp_challenge WHERE pix_key_id = :key ORDER BY created_at DESC LIMIT 1")
                .param("key", pixKeyId)
                .query((rs, i) -> new OtpChallenge(rs.getObject("id", UUID.class), rs.getObject("pix_key_id", UUID.class),
                        OtpChannel.valueOf(rs.getString("channel")), rs.getString("code_hash"), rs.getInt("attempts"),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("verified_at") == null ? null : rs.getTimestamp("verified_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    @Override
    public long countSince(UUID pixKeyId, Instant since) {
        return jdbc.sql("SELECT count(*) FROM otp_challenge WHERE pix_key_id = :key AND created_at >= :since")
                .param("key", pixKeyId).param("since", Timestamp.from(since)).query(Long.class).single();
    }
}
