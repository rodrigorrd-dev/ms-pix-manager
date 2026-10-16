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
@Table(name = "payment_order")
public class PaymentOrderEntity extends AuditableEntity {

    @Id
    private UUID id;
    @Column(name = "payer_account_id", nullable = false, updatable = false)
    private UUID payerAccountId;
    @Column(name = "payment_method", nullable = false, updatable = false)
    private String paymentMethod;
    @Column(nullable = false, updatable = false)
    private String origin;
    @Column(name = "device_registered", nullable = false, updatable = false)
    private boolean deviceRegistered;
    @Column(name = "destination_key_type", updatable = false)
    private String destinationKeyType;
    @Column(name = "destination_key_hash", updatable = false)
    private String destinationKeyHash;
    @Column(name = "dest_document_type", updatable = false)
    private String destDocumentType;
    @Column(name = "dest_document_enc", updatable = false)
    private String destDocumentEnc;
    @Column(name = "dest_document_hash", updatable = false)
    private String destDocumentHash;
    @Column(name = "dest_document_masked", updatable = false)
    private String destDocumentMasked;
    @Column(name = "dest_name", updatable = false)
    private String destName;
    @Column(name = "dest_ispb", updatable = false)
    private String destIspb;
    @Column(name = "dest_branch", updatable = false)
    private String destBranch;
    @Column(name = "dest_account", updatable = false)
    private String destAccount;
    @Column(name = "dest_account_type", updatable = false)
    private String destAccountType;
    @Column(nullable = false, updatable = false)
    private BigDecimal amount;
    @Column(name = "qr_txid", updatable = false)
    private String qrTxid;
    @Column(name = "payer_message", updatable = false)
    private String payerMessage;
    @Column(name = "end_to_end_id", nullable = false, updatable = false)
    private String endToEndId;
    @Column(nullable = false)
    private String status;
    @Column(name = "reject_reason_code")
    private String rejectReasonCode;
    @Column(name = "reject_reason_detail")
    private String rejectReasonDetail;
    @Column(name = "ledger_reservation_id")
    private UUID ledgerReservationId;
    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;
    @Column(name = "sent_at")
    private Instant sentAt;
    @Column(name = "settled_at")
    private Instant settledAt;
    @Version
    @Column(nullable = false)
    private Long version;

    protected PaymentOrderEntity() {
    }

    public PaymentOrderEntity(UUID id) {
        this.id = id;
    }

    public UUID getId() { return id; }
    public UUID getPayerAccountId() { return payerAccountId; }
    public void setPayerAccountId(UUID v) { this.payerAccountId = v; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String v) { this.paymentMethod = v; }
    public String getOrigin() { return origin; }
    public void setOrigin(String v) { this.origin = v; }
    public boolean isDeviceRegistered() { return deviceRegistered; }
    public void setDeviceRegistered(boolean v) { this.deviceRegistered = v; }
    public String getDestinationKeyType() { return destinationKeyType; }
    public void setDestinationKeyType(String v) { this.destinationKeyType = v; }
    public String getDestinationKeyHash() { return destinationKeyHash; }
    public void setDestinationKeyHash(String v) { this.destinationKeyHash = v; }
    public String getDestDocumentType() { return destDocumentType; }
    public void setDestDocumentType(String v) { this.destDocumentType = v; }
    public String getDestDocumentEnc() { return destDocumentEnc; }
    public void setDestDocumentEnc(String v) { this.destDocumentEnc = v; }
    public String getDestDocumentHash() { return destDocumentHash; }
    public void setDestDocumentHash(String v) { this.destDocumentHash = v; }
    public String getDestDocumentMasked() { return destDocumentMasked; }
    public void setDestDocumentMasked(String v) { this.destDocumentMasked = v; }
    public String getDestName() { return destName; }
    public void setDestName(String v) { this.destName = v; }
    public String getDestIspb() { return destIspb; }
    public void setDestIspb(String v) { this.destIspb = v; }
    public String getDestBranch() { return destBranch; }
    public void setDestBranch(String v) { this.destBranch = v; }
    public String getDestAccount() { return destAccount; }
    public void setDestAccount(String v) { this.destAccount = v; }
    public String getDestAccountType() { return destAccountType; }
    public void setDestAccountType(String v) { this.destAccountType = v; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { this.amount = v; }
    public String getQrTxid() { return qrTxid; }
    public void setQrTxid(String v) { this.qrTxid = v; }
    public String getPayerMessage() { return payerMessage; }
    public void setPayerMessage(String v) { this.payerMessage = v; }
    public String getEndToEndId() { return endToEndId; }
    public void setEndToEndId(String v) { this.endToEndId = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getRejectReasonCode() { return rejectReasonCode; }
    public void setRejectReasonCode(String v) { this.rejectReasonCode = v; }
    public String getRejectReasonDetail() { return rejectReasonDetail; }
    public void setRejectReasonDetail(String v) { this.rejectReasonDetail = v; }
    public UUID getLedgerReservationId() { return ledgerReservationId; }
    public void setLedgerReservationId(UUID v) { this.ledgerReservationId = v; }
    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant v) { this.requestedAt = v; }
    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant v) { this.sentAt = v; }
    public Instant getSettledAt() { return settledAt; }
    public void setSettledAt(Instant v) { this.settledAt = v; }
    public Long getVersion() { return version; }
}
