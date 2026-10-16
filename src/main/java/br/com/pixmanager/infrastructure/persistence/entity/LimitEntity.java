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
@Table(name = "pix_limit")
public class LimitEntity extends AuditableEntity {

    @Id
    private UUID id;
    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;
    @Column(nullable = false, updatable = false)
    private String scope;
    @Column(nullable = false)
    private BigDecimal amount;
    @Column(name = "ceiling_amount")
    private BigDecimal ceilingAmount;
    @Column(name = "pending_amount")
    private BigDecimal pendingAmount;
    @Column(name = "pending_requested_at")
    private Instant pendingRequestedAt;
    @Column(name = "pending_effective_at")
    private Instant pendingEffectiveAt;
    @Version
    @Column(nullable = false)
    private Long version;

    protected LimitEntity() {
    }

    public LimitEntity(UUID id) {
        this.id = id;
    }

    public UUID getId() { return id; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID v) { this.accountId = v; }
    public String getScope() { return scope; }
    public void setScope(String v) { this.scope = v; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { this.amount = v; }
    public BigDecimal getCeilingAmount() { return ceilingAmount; }
    public void setCeilingAmount(BigDecimal v) { this.ceilingAmount = v; }
    public BigDecimal getPendingAmount() { return pendingAmount; }
    public void setPendingAmount(BigDecimal v) { this.pendingAmount = v; }
    public Instant getPendingRequestedAt() { return pendingRequestedAt; }
    public void setPendingRequestedAt(Instant v) { this.pendingRequestedAt = v; }
    public Instant getPendingEffectiveAt() { return pendingEffectiveAt; }
    public void setPendingEffectiveAt(Instant v) { this.pendingEffectiveAt = v; }
    public Long getVersion() { return version; }
}
