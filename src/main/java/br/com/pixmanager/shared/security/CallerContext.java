package br.com.pixmanager.shared.security;

import br.com.pixmanager.core.domain.entity.Caller;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import br.com.pixmanager.shared.util.AuthenticatedClient;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Quem está chamando: {@code client_id}/{@code azp}/{@code sub} do JWT e se pode ver o documento completo. Só os
 * adaptadores de entrada usam; o core recebe o {@code clientId} como parâmetro.
 */
public final class CallerContext {

    private CallerContext() {}

    public static String clientId() {
        return AuthenticatedClient.clientId();
    }

    public static boolean canSeeFullDocument() {
        return AuthenticatedClient.canSeeFullDocument();
    }

    /**
     * Contas acessíveis: claim {@code pix_accounts} (lista de UUID, ou {@code "*"} para todas — clientes de back-office).
     * Premissa documentada em docs/10 (G1): o IdP carrega o vínculo cliente → contas. Sem o claim ⇒ nenhuma conta.
     */
    public static Caller current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String client = clientId();
        boolean full = canSeeFullDocument();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            List<String> claim = jwt.getClaimAsStringList("pix_accounts");
            if (claim != null) {
                if (claim.contains("*")) {
                    return new Caller(client, Set.of(), true, full);
                }
                Set<UUID> ids = new HashSet<>();
                for (String accountId : claim) {
                    try {
                        ids.add(UUID.fromString(accountId));
                    } catch (IllegalArgumentException ignored) {
                        // valor inválido no claim: ignorado (nega acesso, nunca amplia)
                    }
                }
                return new Caller(client, ids, false, full);
            }
        }
        return new Caller(client, Set.of(), false, full);
    }
}
