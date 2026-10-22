package br.com.pixmanager.shared.util;

import br.com.pixmanager.shared.constant.ScopeNames;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Cliente OAuth autenticado (claim {@code azp}/{@code client_id}/{@code sub}) e se ele pode ver o documento completo.
 * Fica em {@code shared} para a auditoria (JPA) e os adaptadores de entrada usarem SEM que {@code infrastructure} dependa de
 * {@code api}. Fora de requisição (job, consumer) devolve {@code "anonymous"}.
 */
public final class AuthenticatedClient {

    private AuthenticatedClient() {}

    public static String clientId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            String client = jwt.getClaimAsString("azp");
            if (client == null) {
                client = jwt.getClaimAsString("client_id");
            }
            return client != null ? client : jwt.getSubject();
        }
        return authentication != null ? authentication.getName() : "anonymous";
    }

    public static boolean canSeeFullDocument() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream().anyMatch(authority -> ScopeNames.PII_DOCUMENT_READ.equals(authority.getAuthority()));
    }
}
