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
@Table(name = "pix_refund")
public class RefundEntity extends AuditableEntity {

    @Id
    private UUID id;
    @Column(name = "refund_id", nullable = false, updatable = false)
    private String clientRefundId;
    @Column(name = "received_id", nullable = false, updatable = false)
    private UUID receivedId;
    @Column(name = "end_to_end_id", nullable = false, updatable = false)
    private String endToEndId;
    @Column(name = "rtr_id")
    private String rtrId;
    @Column(nullable = false, updatable = false)
    private BigDecimal amount;
    @Column(nullable = false, updatable = false)
    private String nature;
    @Column(updatable = false)
    private String description;
    @Column(nullable = false)
    private String status;
    @Column(name = "status_reason")
    private String statusReason;
    @Column(name = "request_hash", nullable = false, updatable = false)
    private String requestHash;
    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;
    @Column(name = "settled_at")
    private Instant settledAt;
    @Version
    @Column(nullable = false)
    private Long version;

    protected RefundEntity() {
    }

    public RefundEntity(UUID id) {
        this.id = id;
    }

    public UUID getId() { return id; }
    public String getClientRefundId() { return clientRefundId; }
    public void setClientRefundId(String v) { this.clientRefundId = v; }
    public UUID getReceivedId() { return receivedId; }
    public void setReceivedId(UUID v) { this.receivedId = v; }
    public String getEndToEndId() { return endToEndId; }
    public void setEndToEndId(String v) { this.endToEndId = v; }
    public String getRtrId() { return rtrId; }
    public void setRtrId(String v) { this.rtrId = v; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { this.amount = v; }
    public String getNature() { return nature; }
    public void setNature(String v) { this.nature = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getStatusReason() { return statusReason; }
    public void setStatusReason(String v) { this.statusReason = v; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String v) { this.requestHash = v; }
    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant v) { this.requestedAt = v; }
    public Instant getSettledAt() { return settledAt; }
    public void setSettledAt(Instant v) { this.settledAt = v; }
    public Long getVersion() { return version; }
}
