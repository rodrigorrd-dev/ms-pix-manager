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

    public PixKey toDomain(PixKeyEntity entity) {
        PixKeyValue value = PixKeyValue.of(PixKeyType.valueOf(entity.getKeyType()), crypto.decrypt(entity.getKeyValueEnc()));
        return new PixKey(entity.getId(), entity.getAccountId(), value, PixKeyStatus.valueOf(entity.getStatus()), entity.getDictCid(),
                entity.getDictRegisteredAt(), entity.getDeletedReason() == null ? null : DeletionReason.valueOf(entity.getDeletedReason()),
                entity.getDeletedAt(), entity.getVersion(), entity.getCreatedAt());
    }

    public void apply(PixKey pixKey, PixKeyEntity entity, boolean isNew) {
        if (isNew) {
            entity.setAccountId(pixKey.accountId());
            entity.setKeyType(pixKey.value().type().name());
            entity.setKeyValueEnc(crypto.encrypt(pixKey.value().value()));
            entity.setKeyHash(hashOf(pixKey.value()));
            entity.setKeyMasked(pixKey.value().masked());
        }
        entity.setStatus(pixKey.status().name());
        entity.setDictCid(pixKey.dictCid());
        entity.setDictRegisteredAt(pixKey.dictRegisteredAt());
        entity.setDeletedReason(pixKey.deletedReason() == null ? null : pixKey.deletedReason().name());
        entity.setDeletedAt(pixKey.deletedAt());
    }
}
