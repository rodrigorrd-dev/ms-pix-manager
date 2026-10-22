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
    public void setPayerAccountId(UUID payerAccountId) { this.payerAccountId = payerAccountId; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }
    public boolean isDeviceRegistered() { return deviceRegistered; }
    public void setDeviceRegistered(boolean deviceRegistered) { this.deviceRegistered = deviceRegistered; }
    public String getDestinationKeyType() { return destinationKeyType; }
    public void setDestinationKeyType(String destinationKeyType) { this.destinationKeyType = destinationKeyType; }
    public String getDestinationKeyHash() { return destinationKeyHash; }
    public void setDestinationKeyHash(String destinationKeyHash) { this.destinationKeyHash = destinationKeyHash; }
    public String getDestDocumentType() { return destDocumentType; }
    public void setDestDocumentType(String destDocumentType) { this.destDocumentType = destDocumentType; }
    public String getDestDocumentEnc() { return destDocumentEnc; }
    public void setDestDocumentEnc(String destDocumentEnc) { this.destDocumentEnc = destDocumentEnc; }
    public String getDestDocumentHash() { return destDocumentHash; }
    public void setDestDocumentHash(String destDocumentHash) { this.destDocumentHash = destDocumentHash; }
    public String getDestDocumentMasked() { return destDocumentMasked; }
    public void setDestDocumentMasked(String destDocumentMasked) { this.destDocumentMasked = destDocumentMasked; }
    public String getDestName() { return destName; }
    public void setDestName(String destName) { this.destName = destName; }
    public String getDestIspb() { return destIspb; }
    public void setDestIspb(String destIspb) { this.destIspb = destIspb; }
    public String getDestBranch() { return destBranch; }
    public void setDestBranch(String destBranch) { this.destBranch = destBranch; }
    public String getDestAccount() { return destAccount; }
    public void setDestAccount(String destAccount) { this.destAccount = destAccount; }
    public String getDestAccountType() { return destAccountType; }
    public void setDestAccountType(String destAccountType) { this.destAccountType = destAccountType; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getQrTxid() { return qrTxid; }
    public void setQrTxid(String qrTxid) { this.qrTxid = qrTxid; }
    public String getPayerMessage() { return payerMessage; }
    public void setPayerMessage(String payerMessage) { this.payerMessage = payerMessage; }
    public String getEndToEndId() { return endToEndId; }
    public void setEndToEndId(String endToEndId) { this.endToEndId = endToEndId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRejectReasonCode() { return rejectReasonCode; }
    public void setRejectReasonCode(String rejectReasonCode) { this.rejectReasonCode = rejectReasonCode; }
    public String getRejectReasonDetail() { return rejectReasonDetail; }
    public void setRejectReasonDetail(String rejectReasonDetail) { this.rejectReasonDetail = rejectReasonDetail; }
    public UUID getLedgerReservationId() { return ledgerReservationId; }
    public void setLedgerReservationId(UUID ledgerReservationId) { this.ledgerReservationId = ledgerReservationId; }
    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant requestedAt) { this.requestedAt = requestedAt; }
    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }
    public Instant getSettledAt() { return settledAt; }
    public void setSettledAt(Instant settledAt) { this.settledAt = settledAt; }
    public Long getVersion() { return version; }
}
