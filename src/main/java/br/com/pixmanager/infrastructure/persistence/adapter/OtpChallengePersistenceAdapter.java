package br.com.pixmanager.infrastructure.persistence.adapter;

import br.com.pixmanager.infrastructure.persistence.Timestamps;
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
    public OtpChallenge save(OtpChallenge otpChallenge) {
        jdbc.sql("""
                INSERT INTO otp_challenge (id, pix_key_id, channel, code_hash, attempts, expires_at, verified_at, created_at)
                VALUES (:id, :key, :channel, :hash, :attempts, :expires, :verified, :created)
                ON CONFLICT (id) DO UPDATE SET attempts = EXCLUDED.attempts, verified_at = EXCLUDED.verified_at
                """)
                .param("id", otpChallenge.id()).param("key", otpChallenge.pixKeyId()).param("channel", otpChallenge.channel().name()).param("hash", otpChallenge.codeHash())
                .param("attempts", otpChallenge.attempts()).param("expires", Timestamp.from(otpChallenge.expiresAt()))
                .param("verified", Timestamps.from(otpChallenge.verifiedAt())).param("created", Timestamp.from(otpChallenge.createdAt()))
                .update();
        return otpChallenge;
    }

    @Override
    public Optional<OtpChallenge> findLatest(UUID pixKeyId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM otp_challenge WHERE pix_key_id = :key ORDER BY created_at DESC LIMIT 1")
                .param("key", pixKeyId)
                .query((resultSet, rowNumber) -> new OtpChallenge(resultSet.getObject("id", UUID.class), resultSet.getObject("pix_key_id", UUID.class),
                        OtpChannel.valueOf(resultSet.getString("channel")), resultSet.getString("code_hash"), resultSet.getInt("attempts"),
                        resultSet.getTimestamp("expires_at").toInstant(),
                        Timestamps.toInstant(resultSet.getTimestamp("verified_at")),
                        resultSet.getTimestamp("created_at").toInstant()))
                .optional();
    }

    @Override
    public long countSince(UUID pixKeyId, Instant since) {
        return jdbc.sql("SELECT count(*) FROM otp_challenge WHERE pix_key_id = :key AND created_at >= :since")
                .param("key", pixKeyId).param("since", Timestamp.from(since)).query(Long.class).single();
    }
}
