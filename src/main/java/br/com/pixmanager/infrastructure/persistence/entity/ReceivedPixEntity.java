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
    public void setEndToEndId(String v) { this.endToEndId = v; }
    public String getTxid() { return txid; }
    public void setTxid(String v) { this.txid = v; }
    public UUID getChargeId() { return chargeId; }
    public void setChargeId(UUID v) { this.chargeId = v; }
    public UUID getReceiverAccountId() { return receiverAccountId; }
    public void setReceiverAccountId(UUID v) { this.receiverAccountId = v; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { this.amount = v; }
    public BigDecimal getRefundableAmount() { return refundableAmount; }
    public void setRefundableAmount(BigDecimal v) { this.refundableAmount = v; }
    public BigDecimal getRefundedTotal() { return refundedTotal; }
    public void setRefundedTotal(BigDecimal v) { this.refundedTotal = v; }
    public String getPayerType() { return payerType; }
    public void setPayerType(String v) { this.payerType = v; }
    public String getPayerDocumentEnc() { return payerDocumentEnc; }
    public void setPayerDocumentEnc(String v) { this.payerDocumentEnc = v; }
    public String getPayerDocumentHash() { return payerDocumentHash; }
    public void setPayerDocumentHash(String v) { this.payerDocumentHash = v; }
    public String getPayerDocumentMasked() { return payerDocumentMasked; }
    public void setPayerDocumentMasked(String v) { this.payerDocumentMasked = v; }
    public String getPayerName() { return payerName; }
    public void setPayerName(String v) { this.payerName = v; }
    public String getPayerIspb() { return payerIspb; }
    public void setPayerIspb(String v) { this.payerIspb = v; }
    public String getPayerBranch() { return payerBranch; }
    public void setPayerBranch(String v) { this.payerBranch = v; }
    public String getPayerAccount() { return payerAccount; }
    public void setPayerAccount(String v) { this.payerAccount = v; }
    public String getPayerAccountType() { return payerAccountType; }
    public void setPayerAccountType(String v) { this.payerAccountType = v; }
    public String getPayerInfo() { return payerInfo; }
    public void setPayerInfo(String v) { this.payerInfo = v; }
    public Instant getSettledAt() { return settledAt; }
    public void setSettledAt(Instant v) { this.settledAt = v; }
    public String getReconciliationStatus() { return reconciliationStatus; }
    public void setReconciliationStatus(String v) { this.reconciliationStatus = v; }
    public String getSourceMessageId() { return sourceMessageId; }
    public void setSourceMessageId(String v) { this.sourceMessageId = v; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
}
