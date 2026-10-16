package br.com.pixmanager.infrastructure.persistence.repository;

import br.com.pixmanager.infrastructure.persistence.entity.ReceivedPixEntity;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Filtros nomeados e compostos; {@code null} = filtro ausente ({@code Specification.allOf} ignora). */
public final class ReceivedPixSpecifications {

    private ReceivedPixSpecifications() {}

    /** SEMPRE aplicado: só Pix das contas do chamador. */
    public static Specification<ReceivedPixEntity> accessibleTo(Set<UUID> accounts, boolean allAccounts) {
        if (allAccounts) {
            return null;
        }
        if (accounts.isEmpty()) {
            return (root, query, cb) -> cb.disjunction();
        }
        return (root, query, cb) -> root.get("receiverAccountId").in(accounts);
    }

    public static Specification<ReceivedPixEntity> settledBetween(Instant from, Instant to) {
        return (root, query, cb) -> cb.and(cb.greaterThanOrEqualTo(root.get("settledAt"), from),
                cb.lessThanOrEqualTo(root.get("settledAt"), to));
    }

    public static Specification<ReceivedPixEntity> hasTxid(String txid) {
        return txid == null ? null : (root, query, cb) -> cb.equal(root.get("txid"), txid);
    }

    public static Specification<ReceivedPixEntity> hasPayerHash(String hash) {
        return hash == null ? null : (root, query, cb) -> cb.equal(root.get("payerDocumentHash"), hash);
    }
}
