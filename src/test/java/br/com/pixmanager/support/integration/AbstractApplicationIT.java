package br.com.pixmanager.support.integration;

import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * Base dos ITs "de ponta a ponta" do serviço: contexto Spring COMPLETO (wiring real do {@code UseCaseConfig}) sobre Postgres
 * real com o Liquibase de produção. O que isso pega que testes unitários não pegam: bean faltando, mapeamento JPA ×
 * schema ({@code ddl-auto=validate}), constraint, transação. O {@code JwtDecoder} é mock; os JWTs vêm de
 * {@code SecurityMockMvcRequestPostProcessors.jwt()} (a validação real via JWKS é o {@code JwtValidationIT}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractApplicationIT {

    @MockitoBean
    protected JwtDecoder jwtDecoder;

    @Autowired
    protected AccountFixtures fixtures;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        var postgres = PostgresContainerSupport.POSTGRES;
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("pix.dict-gateway.base-url", DictGatewayStub::baseUrl);
    }
}
