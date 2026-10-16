package br.com.pixmanager.core.domain.entity;

import java.util.Set;
import java.util.UUID;

/**
 * Quem chama, já resolvido pelo adaptador de entrada (JWT): {@code clientId}, contas que pode acessar e se vê o documento
 * completo. Record puro (sem Spring) para o core receber o contexto como PARÂMETRO, sem ler SecurityContext.
 * Recurso fora das contas permitidas ⇒ o serviço responde 404 (esconde existência — information hiding).
 */
public record Caller(String clientId, Set<UUID> accounts, boolean allAccounts, boolean fullDocument) {

    public Caller {
        accounts = accounts == null ? Set.of() : Set.copyOf(accounts);
    }

    public static Caller system(String clientId) {
        return new Caller(clientId, Set.of(), true, false);
    }

    public static Caller forAccounts(String clientId, UUID... accounts) {
        return new Caller(clientId, Set.of(accounts), false, false);
    }

    public boolean canAccess(UUID accountId) {
        return allAccounts || accounts.contains(accountId);
    }
}
