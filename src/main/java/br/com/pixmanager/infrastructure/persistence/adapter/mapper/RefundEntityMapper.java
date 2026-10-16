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

    public Refund toDomain(RefundEntity e) {
        return new Refund(e.getId(), e.getClientRefundId(), e.getReceivedId(), new EndToEndId(e.getEndToEndId()), e.getRtrId(),
                Money.of(e.getAmount()), RefundNature.valueOf(e.getNature()), e.getDescription(), RefundStatus.valueOf(e.getStatus()),
                e.getStatusReason(), e.getRequestedAt(), e.getSettledAt(), e.getVersion());
    }

    public void apply(Refund d, RefundEntity e, boolean isNew) {
        if (isNew) {
            e.setClientRefundId(d.clientRefundId());
            e.setReceivedId(d.receivedId());
            e.setEndToEndId(d.endToEndId().value());
            e.setAmount(d.amount().value());
            e.setNature(d.nature().name());
            e.setDescription(d.description());
            e.setRequestHash(requestHash(d));
            e.setRequestedAt(d.requestedAt());
        }
        e.setRtrId(d.rtrId());
        e.setStatus(d.status().name());
        e.setStatusReason(d.statusReason());
        e.setSettledAt(d.settledAt());
    }

    /** SHA-256 do pedido (valor|natureza|descrição): trilha de auditoria da idempotência por {@code (e2eId, id)}. */
    static String requestHash(Refund d) {
        try {
            String canonical = d.amount().toBcb() + "|" + d.nature() + "|" + (d.description() == null ? "" : d.description());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
