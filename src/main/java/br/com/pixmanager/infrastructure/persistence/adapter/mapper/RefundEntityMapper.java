package br.com.pixmanager.infrastructure.persistence.adapter.mapper;

import br.com.pixmanager.core.domain.entity.EndToEndId;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.entity.Refund;
import br.com.pixmanager.core.domain.enums.RefundNature;
import br.com.pixmanager.core.domain.enums.RefundStatus;
import br.com.pixmanager.infrastructure.persistence.entity.RefundEntity;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Component
public class RefundEntityMapper {

    public Refund toDomain(RefundEntity entity) {
        return new Refund(entity.getId(), entity.getClientRefundId(), entity.getReceivedId(), new EndToEndId(entity.getEndToEndId()), entity.getRtrId(),
                Money.of(entity.getAmount()), RefundNature.valueOf(entity.getNature()), entity.getDescription(), RefundStatus.valueOf(entity.getStatus()),
                entity.getStatusReason(), entity.getRequestedAt(), entity.getSettledAt(), entity.getVersion());
    }

    public void apply(Refund refund, RefundEntity entity, boolean isNew) {
        if (isNew) {
            entity.setClientRefundId(refund.clientRefundId());
            entity.setReceivedId(refund.receivedId());
            entity.setEndToEndId(refund.endToEndId().value());
            entity.setAmount(refund.amount().value());
            entity.setNature(refund.nature().name());
            entity.setDescription(refund.description());
            entity.setRequestHash(requestHash(refund));
            entity.setRequestedAt(refund.requestedAt());
        }
        entity.setRtrId(refund.rtrId());
        entity.setStatus(refund.status().name());
        entity.setStatusReason(refund.statusReason());
        entity.setSettledAt(refund.settledAt());
    }

    /** SHA-256 do pedido (valor|natureza|descrição): trilha de auditoria da idempotência por {@code (e2eId, id)}. */
    static String requestHash(Refund refund) {
        try {
            String canonical = refund.amount().toBcb() + "|" + refund.nature() + "|" + (refund.description() == null ? "" : refund.description());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
