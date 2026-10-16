package br.com.pixmanager.infrastructure.persistence.entity;

import br.com.pixmanager.infrastructure.persistence.entity.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "pix_charge")
public class ChargeEntity extends AuditableEntity {

    @Id
    private UUID id;
    @Column(name = "charge_type", nullable = false, updatable = false)
    private String chargeType;
    @Column(nullable = false, updatable = false)
    private String txid;
    @Column(name = "receiver_account_id", nullable = false, updatable = false)
    private UUID receiverAccountId;
    @Column(name = "receiver_document_hash", nullable = false, updatable = false)
    private String receiverDocumentHash;
    @Column(name = "pix_key_id", nullable = false, updatable = false)
    private UUID pixKeyId;
    @Column(nullable = false)
    private String status;
    @Column(name = "original_amount", nullable = false)
    private BigDecimal originalAmount;
    @Column(name = "expiration_seconds")
    private Integer expirationSeconds;
    @Column(name = "expires_at")
    private Instant expiresAt;
    @Column(name = "due_date")
    private LocalDate dueDate;
    @Column(name = "validity_after_due_days")
    private Integer validityAfterDueDays;
    @Column(name = "debtor_type")
    private String debtorType;
    @Column(name = "debtor_document_enc")
    private String debtorDocumentEnc;
    @Column(name = "debtor_document_hash")
    private String debtorDocumentHash;
    @Column(name = "debtor_name")
    private String debtorName;
    @Column(name = "payer_request")
    private String payerRequest;
    @Column(name = "location_id")
    private UUID locationId;
    @Column(nullable = false)
    private int revision;
    @Column(name = "paid_received_id")
    private UUID paidReceivedId;
    @Column(name = "removed_at")
    private Instant removedAt;
    @Version
    @Column(nullable = false)
    private Long version;

    protected ChargeEntity() {
    }

    public ChargeEntity(UUID id) {
        this.id = id;
    }

    public UUID getId() { return id; }
    public String getChargeType() { return chargeType; }
    public void setChargeType(String v) { this.chargeType = v; }
    public String getTxid() { return txid; }
    public void setTxid(String v) { this.txid = v; }
    public UUID getReceiverAccountId() { return receiverAccountId; }
    public void setReceiverAccountId(UUID v) { this.receiverAccountId = v; }
    public String getReceiverDocumentHash() { return receiverDocumentHash; }
    public void setReceiverDocumentHash(String v) { this.receiverDocumentHash = v; }
    public UUID getPixKeyId() { return pixKeyId; }
    public void setPixKeyId(UUID v) { this.pixKeyId = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public BigDecimal getOriginalAmount() { return originalAmount; }
    public void setOriginalAmount(BigDecimal v) { this.originalAmount = v; }
    public Integer getExpirationSeconds() { return expirationSeconds; }
    public void setExpirationSeconds(Integer v) { this.expirationSeconds = v; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant v) { this.expiresAt = v; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate v) { this.dueDate = v; }
    public Integer getValidityAfterDueDays() { return validityAfterDueDays; }
    public void setValidityAfterDueDays(Integer v) { this.validityAfterDueDays = v; }
    public String getDebtorType() { return debtorType; }
    public void setDebtorType(String v) { this.debtorType = v; }
    public String getDebtorDocumentEnc() { return debtorDocumentEnc; }
    public void setDebtorDocumentEnc(String v) { this.debtorDocumentEnc = v; }
    public String getDebtorDocumentHash() { return debtorDocumentHash; }
    public void setDebtorDocumentHash(String v) { this.debtorDocumentHash = v; }
    public String getDebtorName() { return debtorName; }
    public void setDebtorName(String v) { this.debtorName = v; }
    public String getPayerRequest() { return payerRequest; }
    public void setPayerRequest(String v) { this.payerRequest = v; }
    public UUID getLocationId() { return locationId; }
    public void setLocationId(UUID v) { this.locationId = v; }
    public int getRevision() { return revision; }
    public void setRevision(int v) { this.revision = v; }
    public UUID getPaidReceivedId() { return paidReceivedId; }
    public void setPaidReceivedId(UUID v) { this.paidReceivedId = v; }
    public Instant getRemovedAt() { return removedAt; }
    public void setRemovedAt(Instant v) { this.removedAt = v; }
    public Long getVersion() { return version; }
}
