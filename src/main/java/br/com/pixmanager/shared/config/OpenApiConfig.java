package br.com.pixmanager.shared.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI do ms: declara o Bearer JWT (OAuth2 Resource Server) para que o Swagger UI mostre o botão <b>Authorize</b>
 * e envie o token no "Try it out". O ms só VALIDA o token; quem emite é o IdP (veja o README).
 */
@Configuration
public class OpenApiConfig {

    static final String BEARER = "bearer-jwt";

    @Bean
    OpenAPI pixManagerOpenApi() {
        return new OpenAPI()
                .info(new Info().title("ms-pix-manager").version("1.0.0")
                        .description("API Pix: compatível com o BCB em /api/pix/v2 e gestão própria em /v1. Erros em application/problem+json (RFC 9457)."))
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                                .description("Token do IdP (client_credentials). Escopos por rota: cob.*, cobv.*, pix.*, pixkey.*, payment.*, limit.*")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
