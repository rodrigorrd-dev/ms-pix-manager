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
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public BigDecimal getCeilingAmount() { return ceilingAmount; }
    public void setCeilingAmount(BigDecimal ceilingAmount) { this.ceilingAmount = ceilingAmount; }
    public BigDecimal getPendingAmount() { return pendingAmount; }
    public void setPendingAmount(BigDecimal pendingAmount) { this.pendingAmount = pendingAmount; }
    public Instant getPendingRequestedAt() { return pendingRequestedAt; }
    public void setPendingRequestedAt(Instant pendingRequestedAt) { this.pendingRequestedAt = pendingRequestedAt; }
    public Instant getPendingEffectiveAt() { return pendingEffectiveAt; }
    public void setPendingEffectiveAt(Instant pendingEffectiveAt) { this.pendingEffectiveAt = pendingEffectiveAt; }
    public Long getVersion() { return version; }
}
