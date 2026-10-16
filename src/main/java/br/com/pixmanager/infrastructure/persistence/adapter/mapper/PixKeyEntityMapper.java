package br.com.pixmanager.infrastructure.persistence.adapter.mapper;

import br.com.pixmanager.infrastructure.persistence.entity.PixKeyEntity;

import br.com.pixmanager.core.domain.enums.DeletionReason;
import br.com.pixmanager.core.domain.entity.PixKey;
import br.com.pixmanager.core.domain.enums.PixKeyStatus;
import br.com.pixmanager.core.domain.enums.PixKeyType;
import br.com.pixmanager.core.domain.entity.PixKeyValue;
import br.com.pixmanager.infrastructure.security.crypto.CryptoService;
import org.springframework.stereotype.Component;

/** Domínio ⇄ entidade. A cifra e o índice cego do valor da chave acontecem SÓ aqui (o core não conhece cripto). */
@Component
public class PixKeyEntityMapper {

    private final CryptoService crypto;

    public PixKeyEntityMapper(CryptoService crypto) {
        this.crypto = crypto;
    }

    public String hashOf(PixKeyValue value) {
        return crypto.blindIndex("key:" + value.value());     // prefixo: domínio HMAC próprio (≠ hash de documento)
    }

    public PixKey toDomain(PixKeyEntity e) {
        PixKeyValue value = PixKeyValue.of(PixKeyType.valueOf(e.getKeyType()), crypto.decrypt(e.getKeyValueEnc()));
        return new PixKey(e.getId(), e.getAccountId(), value, PixKeyStatus.valueOf(e.getStatus()), e.getDictCid(),
                e.getDictRegisteredAt(), e.getDeletedReason() == null ? null : DeletionReason.valueOf(e.getDeletedReason()),
                e.getDeletedAt(), e.getVersion(), e.getCreatedAt());
    }

    public void apply(PixKey d, PixKeyEntity e, boolean isNew) {
        if (isNew) {
            e.setAccountId(d.accountId());
            e.setKeyType(d.value().type().name());
            e.setKeyValueEnc(crypto.encrypt(d.value().value()));
            e.setKeyHash(hashOf(d.value()));
            e.setKeyMasked(d.value().masked());
        }
        e.setStatus(d.status().name());
        e.setDictCid(d.dictCid());
        e.setDictRegisteredAt(d.dictRegisteredAt());
        e.setDeletedReason(d.deletedReason() == null ? null : d.deletedReason().name());
        e.setDeletedAt(d.deletedAt());
    }
}
