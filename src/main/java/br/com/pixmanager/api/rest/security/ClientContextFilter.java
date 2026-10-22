package br.com.pixmanager.api.rest.security;

import br.com.pixmanager.shared.util.AuthenticatedClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Coloca {@code clientId} (azp/client_id/sub do JWT) no MDC. Roda DEPOIS do Spring Security: antes disso não há autenticação. */
@Component
@Order(SecurityProperties.DEFAULT_FILTER_ORDER + 1)
public class ClientContextFilter extends OncePerRequestFilter {

    static final String MDC_KEY = "clientId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated = authentication != null && authentication.isAuthenticated() && !(authentication instanceof AnonymousAuthenticationToken);
        if (authenticated) {
            MDC.put(MDC_KEY, AuthenticatedClient.clientId());
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
