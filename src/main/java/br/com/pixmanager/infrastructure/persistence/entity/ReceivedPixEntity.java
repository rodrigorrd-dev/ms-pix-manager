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
@Table(name = "pix_received")
public class ReceivedPixEntity {

    @Id
    private UUID id;
    @Column(name = "end_to_end_id", nullable = false, updatable = false)
    private String endToEndId;
    @Column(updatable = false)
    private String txid;
    @Column(name = "charge_id")
    private UUID chargeId;
    @Column(name = "receiver_account_id", nullable = false, updatable = false)
    private UUID receiverAccountId;
    @Column(nullable = false, updatable = false)
    private BigDecimal amount;
    @Column(name = "refundable_amount", nullable = false, updatable = false)
    private BigDecimal refundableAmount;
    /** Gravado só no INSERT; depois muda apenas pelo UPDATE condicional atômico da devolução (nunca pelo JPA). */
    @Column(name = "refunded_total", nullable = false, updatable = false)
    private BigDecimal refundedTotal;
    @Column(name = "payer_type", updatable = false)
    private String payerType;
    @Column(name = "payer_document_enc", updatable = false)
    private String payerDocumentEnc;
    @Column(name = "payer_document_hash", updatable = false)
    private String payerDocumentHash;
    @Column(name = "payer_document_masked", updatable = false)
    private String payerDocumentMasked;
    @Column(name = "payer_name", updatable = false)
    private String payerName;
    @Column(name = "payer_ispb", updatable = false)
    private String payerIspb;
    @Column(name = "payer_branch", updatable = false)
    private String payerBranch;
    @Column(name = "payer_account", updatable = false)
    private String payerAccount;
    @Column(name = "payer_account_type", updatable = false)
    private String payerAccountType;
    @Column(name = "payer_info", updatable = false)
    private String payerInfo;
    @Column(name = "settled_at", nullable = false, updatable = false)
    private Instant settledAt;
    @Column(name = "reconciliation_status", nullable = false)
    private String reconciliationStatus;
    @Column(name = "source_message_id", nullable = false, updatable = false)
    private String sourceMessageId;
    @Version
    @Column(nullable = false)
    private Long version;
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected ReceivedPixEntity() {
    }

    public ReceivedPixEntity(UUID id) {
        this.id = id;
    }

    public UUID getId() { return id; }
    public String getEndToEndId() { return endToEndId; }
    public void setEndToEndId(String endToEndId) { this.endToEndId = endToEndId; }
    public String getTxid() { return txid; }
    public void setTxid(String txid) { this.txid = txid; }
    public UUID getChargeId() { return chargeId; }
    public void setChargeId(UUID chargeId) { this.chargeId = chargeId; }
    public UUID getReceiverAccountId() { return receiverAccountId; }
    public void setReceiverAccountId(UUID receiverAccountId) { this.receiverAccountId = receiverAccountId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public BigDecimal getRefundableAmount() { return refundableAmount; }
    public void setRefundableAmount(BigDecimal refundableAmount) { this.refundableAmount = refundableAmount; }
    public BigDecimal getRefundedTotal() { return refundedTotal; }
    public void setRefundedTotal(BigDecimal refundedTotal) { this.refundedTotal = refundedTotal; }
    public String getPayerType() { return payerType; }
    public void setPayerType(String payerType) { this.payerType = payerType; }
    public String getPayerDocumentEnc() { return payerDocumentEnc; }
    public void setPayerDocumentEnc(String payerDocumentEnc) { this.payerDocumentEnc = payerDocumentEnc; }
    public String getPayerDocumentHash() { return payerDocumentHash; }
    public void setPayerDocumentHash(String payerDocumentHash) { this.payerDocumentHash = payerDocumentHash; }
    public String getPayerDocumentMasked() { return payerDocumentMasked; }
    public void setPayerDocumentMasked(String payerDocumentMasked) { this.payerDocumentMasked = payerDocumentMasked; }
    public String getPayerName() { return payerName; }
    public void setPayerName(String payerName) { this.payerName = payerName; }
    public String getPayerIspb() { return payerIspb; }
    public void setPayerIspb(String payerIspb) { this.payerIspb = payerIspb; }
    public String getPayerBranch() { return payerBranch; }
    public void setPayerBranch(String payerBranch) { this.payerBranch = payerBranch; }
    public String getPayerAccount() { return payerAccount; }
    public void setPayerAccount(String payerAccount) { this.payerAccount = payerAccount; }
    public String getPayerAccountType() { return payerAccountType; }
    public void setPayerAccountType(String payerAccountType) { this.payerAccountType = payerAccountType; }
    public String getPayerInfo() { return payerInfo; }
    public void setPayerInfo(String payerInfo) { this.payerInfo = payerInfo; }
    public Instant getSettledAt() { return settledAt; }
    public void setSettledAt(Instant settledAt) { this.settledAt = settledAt; }
    public String getReconciliationStatus() { return reconciliationStatus; }
    public void setReconciliationStatus(String reconciliationStatus) { this.reconciliationStatus = reconciliationStatus; }
    public String getSourceMessageId() { return sourceMessageId; }
    public void setSourceMessageId(String sourceMessageId) { this.sourceMessageId = sourceMessageId; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
}
