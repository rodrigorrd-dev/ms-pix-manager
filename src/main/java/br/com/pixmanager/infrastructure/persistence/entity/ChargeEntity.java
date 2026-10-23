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
    public void setChargeType(String chargeType) { this.chargeType = chargeType; }
    public String getTxid() { return txid; }
    public void setTxid(String txid) { this.txid = txid; }
    public UUID getReceiverAccountId() { return receiverAccountId; }
    public void setReceiverAccountId(UUID receiverAccountId) { this.receiverAccountId = receiverAccountId; }
    public String getReceiverDocumentHash() { return receiverDocumentHash; }
    public void setReceiverDocumentHash(String receiverDocumentHash) { this.receiverDocumentHash = receiverDocumentHash; }
    public UUID getPixKeyId() { return pixKeyId; }
    public void setPixKeyId(UUID pixKeyId) { this.pixKeyId = pixKeyId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public BigDecimal getOriginalAmount() { return originalAmount; }
    public void setOriginalAmount(BigDecimal originalAmount) { this.originalAmount = originalAmount; }
    public Integer getExpirationSeconds() { return expirationSeconds; }
    public void setExpirationSeconds(Integer expirationSeconds) { this.expirationSeconds = expirationSeconds; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    public Integer getValidityAfterDueDays() { return validityAfterDueDays; }
    public void setValidityAfterDueDays(Integer validityAfterDueDays) { this.validityAfterDueDays = validityAfterDueDays; }
    public String getDebtorType() { return debtorType; }
    public void setDebtorType(String debtorType) { this.debtorType = debtorType; }
    public String getDebtorDocumentEnc() { return debtorDocumentEnc; }
    public void setDebtorDocumentEnc(String debtorDocumentEnc) { this.debtorDocumentEnc = debtorDocumentEnc; }
    public String getDebtorDocumentHash() { return debtorDocumentHash; }
    public void setDebtorDocumentHash(String debtorDocumentHash) { this.debtorDocumentHash = debtorDocumentHash; }
    public String getDebtorName() { return debtorName; }
    public void setDebtorName(String debtorName) { this.debtorName = debtorName; }
    public String getPayerRequest() { return payerRequest; }
    public void setPayerRequest(String payerRequest) { this.payerRequest = payerRequest; }
    public UUID getLocationId() { return locationId; }
    public void setLocationId(UUID locationId) { this.locationId = locationId; }
    public int getRevision() { return revision; }
    public void setRevision(int revision) { this.revision = revision; }
    public UUID getPaidReceivedId() { return paidReceivedId; }
    public void setPaidReceivedId(UUID paidReceivedId) { this.paidReceivedId = paidReceivedId; }
    public Instant getRemovedAt() { return removedAt; }
    public void setRemovedAt(Instant removedAt) { this.removedAt = removedAt; }
    public Long getVersion() { return version; }
}
