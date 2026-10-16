package br.com.pixmanager.core.domain.entity;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Tentativa de liquidar uma cobrança. {@code type}: AGND (a agendada, na data) | NTAG (nova tentativa, só se a autorização permite).
 * {@code status}: AGENDADA → SOLICITADA (ordem criada) → PAGA | REJEITADA | CANCELADA | EXPIRADA.
 */
public record AutomaticAttempt(UUID id, UUID chargeId, String type, String status, String endToEndId, LocalDate settlementDate, UUID paymentOrderId,
                               String lastErrorCode, long version) {

    public AutomaticAttempt with(String newStatus, String error) {
        return new AutomaticAttempt(id, chargeId, type, newStatus, endToEndId, settlementDate, paymentOrderId, error, version);
    }

    public AutomaticAttempt linkedTo(UUID orderId, String e2e) {
        return new AutomaticAttempt(id, chargeId, type, "SOLICITADA", e2e, settlementDate, orderId, lastErrorCode, version);
    }
}
