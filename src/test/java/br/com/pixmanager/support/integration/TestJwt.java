package br.com.pixmanager.support.integration;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/** JWT de teste: escopos {@code SCOPE_*} e claim {@code pix_accounts} (contas acessíveis; {@code *} = todas). */
public final class TestJwt {

    private TestJwt() {}

    public static RequestPostProcessor client(String clientId, List<UUID> accounts, String... scopes) {
        return jwt().jwt(jwtBuilder -> jwtBuilder.claim("azp", clientId).claim("pix_accounts", accounts.stream().map(UUID::toString).toList()))
                .authorities(authorities(scopes));
    }

    public static RequestPostProcessor admin(String clientId, String... scopes) {
        return jwt().jwt(jwtBuilder -> jwtBuilder.claim("azp", clientId).claim("pix_accounts", List.of("*")))
                .authorities(authorities(scopes));
    }

    private static GrantedAuthority[] authorities(String... scopes) {
        return Arrays.stream(scopes).map(scope -> (GrantedAuthority) new SimpleGrantedAuthority("SCOPE_" + scope)).toArray(GrantedAuthority[]::new);
    }
}
