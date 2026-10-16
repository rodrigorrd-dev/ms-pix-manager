package br.com.pixmanager.core.domain.entity;

import java.time.LocalDate;
import java.util.UUID;

/** Cobrança recorrente (CobR). {@code status}: ATIVA → CONCLUIDA | EXPIRADA | REJEITADA | CANCELADA. Conta do recebedor vem na própria cobrança. */
public record AutomaticCharge(UUID id, UUID authorizationId, String txid, LocalDate dueDate, Money amount, String receiverIspb, String receiverBranch,
                              String receiverAccount, String receiverAccountType, String status, long version) {

    public AutomaticCharge with(String newStatus) {
        return new AutomaticCharge(id, authorizationId, txid, dueDate, amount, receiverIspb, receiverBranch, receiverAccount, receiverAccountType, newStatus, version);
    }

    public boolean isLive() {
        return "ATIVA".equals(status);
    }
}
