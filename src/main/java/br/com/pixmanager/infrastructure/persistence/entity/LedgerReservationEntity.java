package br.com.pixmanager.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_reservation")
public class LedgerReservationEntity {

    @Id
    private UUID id;
    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;
    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;
    @Column(nullable = false, updatable = false)
    private String purpose;
    @Column(name = "reference_type", nullable = false, updatable = false)
    private String referenceType;
    @Column(name = "reference_id", nullable = false, updatable = false)
    private UUID referenceId;
    @Column(nullable = false, updatable = false)
    private BigDecimal amount;
    @Column(nullable = false)
    private String status;
    @Column(name = "ledger_ref")
    private String ledgerRef;
    @Column(nullable = false)
    private short attempts;
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;
    @Column(name = "last_error")
    private String lastError;
    @Version
    @Column(nullable = false)
    private Long version;
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LedgerReservationEntity() {
    }

    public LedgerReservationEntity(UUID id) {
        this.id = id;
    }

    public UUID getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String v) { this.idempotencyKey = v; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID v) { this.accountId = v; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String v) { this.purpose = v; }
    public String getReferenceType() { return referenceType; }
    public void setReferenceType(String v) { this.referenceType = v; }
    public UUID getReferenceId() { return referenceId; }
    public void setReferenceId(UUID v) { this.referenceId = v; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { this.amount = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getLedgerRef() { return ledgerRef; }
    public void setLedgerRef(String v) { this.ledgerRef = v; }
    public short getAttempts() { return attempts; }
    public void setAttempts(short v) { this.attempts = v; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant v) { this.nextAttemptAt = v; }
    public String getLastError() { return lastError; }
    public void setLastError(String v) { this.lastError = v; }
    public Long getVersion() { return version; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
