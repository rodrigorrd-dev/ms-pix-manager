package br.com.pixmanager.infrastructure.persistence.entity;

import br.com.pixmanager.infrastructure.persistence.entity.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pix_key")
public class PixKeyEntity extends AuditableEntity {

    @Id
    private UUID id;
    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;
    @Column(name = "key_type", nullable = false, updatable = false)
    private String keyType;
    @Column(name = "key_value_enc", nullable = false, updatable = false)
    private String keyValueEnc;
    @Column(name = "key_hash", nullable = false, updatable = false)
    private String keyHash;
    @Column(name = "key_masked", nullable = false, updatable = false)
    private String keyMasked;
    @Column(nullable = false)
    private String status;
    @Column(name = "dict_cid")
    private String dictCid;
    @Column(name = "dict_registered_at")
    private Instant dictRegisteredAt;
    @Column(name = "deleted_reason")
    private String deletedReason;
    @Column(name = "deleted_at")
    private Instant deletedAt;
    @Version
    @Column(nullable = false)
    private Long version;

    protected PixKeyEntity() {
    }

    public PixKeyEntity(UUID id) {
        this.id = id;
    }

    public UUID getId() { return id; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID v) { this.accountId = v; }
    public String getKeyType() { return keyType; }
    public void setKeyType(String v) { this.keyType = v; }
    public String getKeyValueEnc() { return keyValueEnc; }
    public void setKeyValueEnc(String v) { this.keyValueEnc = v; }
    public String getKeyHash() { return keyHash; }
    public void setKeyHash(String v) { this.keyHash = v; }
    public String getKeyMasked() { return keyMasked; }
    public void setKeyMasked(String v) { this.keyMasked = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getDictCid() { return dictCid; }
    public void setDictCid(String v) { this.dictCid = v; }
    public Instant getDictRegisteredAt() { return dictRegisteredAt; }
    public void setDictRegisteredAt(Instant v) { this.dictRegisteredAt = v; }
    public String getDeletedReason() { return deletedReason; }
    public void setDeletedReason(String v) { this.deletedReason = v; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant v) { this.deletedAt = v; }
    public Long getVersion() { return version; }
}
