package br.com.pixmanager.infrastructure.persistence.repository;

import br.com.pixmanager.infrastructure.persistence.entity.ChargeEntity;

import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Filtros compostos (clean-code/jpa-patterns): cada um é um método nomeado que devolve {@code null} quando ausente
 * ({@code Specification.allOf} ignora {@code null}) — nada de {@code if} aninhado dentro de uma lambda gigante.
 */
public final class ChargeSpecifications {

    private ChargeSpecifications() {}

    /** SEMPRE aplicado: o chamador só vê cobranças das SUAS contas (a menos que seja de escopo total). */
    public static Specification<ChargeEntity> accessibleTo(Set<UUID> accounts, boolean allAccounts) {
        if (allAccounts) {
            return null;
        }
        if (accounts.isEmpty()) {
            return (root, query, cb) -> cb.disjunction();
        }
        return (root, query, cb) -> root.get("receiverAccountId").in(accounts);
    }

    public static Specification<ChargeEntity> createdBetween(Instant from, Instant to) {
        return (root, query, cb) -> cb.and(cb.greaterThanOrEqualTo(root.get("createdAt"), from),
                cb.lessThanOrEqualTo(root.get("createdAt"), to));
    }

    public static Specification<ChargeEntity> hasStatus(String status) {
        return status == null ? null : (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<ChargeEntity> hasDebtorHash(String hash) {
        return hash == null ? null : (root, query, cb) -> cb.equal(root.get("debtorDocumentHash"), hash);
    }

    public static Specification<ChargeEntity> locationPresent(Boolean present) {
        if (present == null) {
            return null;
        }
        return (root, query, cb) -> present ? cb.isNotNull(root.get("locationId")) : cb.isNull(root.get("locationId"));
    }
}
