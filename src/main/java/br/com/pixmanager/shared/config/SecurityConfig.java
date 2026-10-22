package br.com.pixmanager.shared.config;

import br.com.pixmanager.shared.properties.SecurityProperties;

import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.properties.ErrorProperties;
import br.com.pixmanager.shared.exception.handler.ProblemDetails;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.util.List;

/**
 * OAuth2 Resource Server puro (adaptado do mesa-credito-full): STATELESS, sem UserDetailsService, sem emissão de token.
 * 401/403 saem como {@code application/problem+json} (RFC 9457) — o corpo padrão do Spring Security não é Problem Details.
 *
 * <p>{@code proxyTargetClass = true}: controllers seguem interface + {@code *Impl}; CGLIB preserva {@code @RestController}.
 */
@Configuration
@EnableMethodSecurity(proxyTargetClass = true)
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/actuator/health/**", "/actuator/info", "/actuator/prometheus"
    };
    /** Payload público do QR dinâmico (JWS): contrato do BCB, sem Bearer. */
    private static final String[] PUBLIC_QR_PATHS = {"/api/pix/v2/qr/**"};

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, AuthenticationEntryPoint entryPoint,
                                    AccessDeniedHandler accessDeniedHandler, SecurityOpenPaths openPaths) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authorize -> authorize
                    .requestMatchers(PUBLIC_PATHS).permitAll()
                    .requestMatchers(PUBLIC_QR_PATHS).permitAll()
                    .requestMatchers(openPaths.paths()).permitAll()
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .anyRequest().authenticated())
            .exceptionHandling(exceptionHandling -> exceptionHandling.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDeniedHandler))
            .oauth2ResourceServer(oauth -> oauth
                    .authenticationEntryPoint(entryPoint)
                    .accessDeniedHandler(accessDeniedHandler)
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    /** Swagger/Springwolf: abertos só quando {@code pix.docs.public=true} (fechado em hml/prd). */
    @Bean
    SecurityOpenPaths securityOpenPaths(org.springframework.core.env.Environment environment) {
        boolean open = environment.getProperty("pix.docs.public", Boolean.class, false);
        return open
                ? new SecurityOpenPaths(new String[]{"/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html",
                        "/springwolf/**"})
                : new SecurityOpenPaths(new String[0]);
    }

    record SecurityOpenPaths(String[] paths) {}

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthorityPrefix("SCOPE_");
        authorities.setAuthoritiesClaimName("scope");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /** Valida assinatura (JWKS), {@code iss}, {@code aud} e {@code exp}/{@code nbf}. Rejeita {@code alg=none} (Nimbus). */
    @Bean
    @ConditionalOnProperty(prefix = "pix.security.jwt", name = "jwk-set-uri")
    JwtDecoder jwtDecoder(SecurityProperties securityProperties, org.springframework.core.env.Environment environment) {
        boolean strict = environment.acceptsProfiles(org.springframework.core.env.Profiles.of("hml", "prd"));
        if (strict && (isBlank(securityProperties.issuer()) || isBlank(securityProperties.audience()))) {
            // sem issuer/audience qualquer token assinado pelo mesmo JWKS (outro cliente/ambiente) seria aceito
            throw new IllegalStateException("pix.security.jwt.issuer e pix.security.jwt.audience são obrigatórios em hml/prd");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(securityProperties.jwkSetUri())
                .jwsAlgorithms(algorithms -> { algorithms.add(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256);
                                         algorithms.add(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.ES256); })
                .build();
        OAuth2TokenValidator<Jwt> issuer = securityProperties.issuer() == null || securityProperties.issuer().isBlank()
                ? JwtValidators.createDefault() : JwtValidators.createDefaultWithIssuer(securityProperties.issuer());
        OAuth2TokenValidator<Jwt> audience = jwt -> {
            if (securityProperties.audience() == null || securityProperties.audience().isBlank()
                    || (jwt.getAudience() != null && jwt.getAudience().contains(securityProperties.audience()))) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "audience inválida", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(List.of(issuer, audience)));
        return decoder;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Bean
    AuthenticationEntryPoint problemAuthenticationEntryPoint(ObjectMapper mapper, ErrorProperties errors, MessageSource messages) {
        return (request, response, ex) -> write(mapper, response, ProblemDetails.of(errors, HttpStatus.UNAUTHORIZED,
                msg(messages, "error.unauthenticated.title"), msg(messages, "error.unauthenticated.detail"),
                ErrorCodes.UNAUTHENTICATED), HttpStatus.UNAUTHORIZED);
    }

    @Bean
    AccessDeniedHandler problemAccessDeniedHandler(ObjectMapper mapper, ErrorProperties errors, MessageSource messages) {
        return (request, response, ex) -> write(mapper, response, ProblemDetails.of(errors, HttpStatus.FORBIDDEN,
                msg(messages, "error.accessDenied.title"), msg(messages, "error.accessDenied.detail"),
                ErrorCodes.ACCESS_DENIED), HttpStatus.FORBIDDEN);
    }

    private static String msg(MessageSource messages, String key) {
        return messages.getMessage(key, null, key, LocaleContextHolder.getLocale());
    }

    private static void write(ObjectMapper mapper, jakarta.servlet.http.HttpServletResponse response,
                              org.springframework.http.ProblemDetail pd, HttpStatus status) throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        mapper.writeValue(response.getOutputStream(), pd);
    }
}
