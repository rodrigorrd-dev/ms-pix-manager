package br.com.pixmanager.infrastructure.rest.dict;

import br.com.pixmanager.shared.properties.DictGatewayProperties;

import br.com.pixmanager.core.port.in.account.FindAccountUseCase;
import br.com.pixmanager.core.domain.entity.AccountInfo;
import br.com.pixmanager.core.port.out.dict.DictGatewayPortOut;
import br.com.pixmanager.core.domain.enums.DeletionReason;
import br.com.pixmanager.core.domain.entity.PixKey;
import br.com.pixmanager.core.domain.entity.PixKeyValue;
import br.com.pixmanager.shared.model.document.Document;
import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.NotFoundException;
import br.com.pixmanager.infrastructure.rest.support.GatewayHttp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * DICT via {@code dict-gateway} (contrato rascunho — docs/05 §6b, pendência E2). Técnico × negócio por status:
 * 404 no lookup ⇒ vazio; 4xx ⇒ {@link BusinessException} (nunca retentada); 429/503 ⇒ indisponível; 5xx/timeout ⇒ técnica.
 */
@Component
public class DictGatewayHttpAdapter implements DictGatewayPortOut {

    private static final String DEPENDENCY = "dict-gateway";

    private final RestClient http;
    private final CircuitBreaker breaker;
    private final ObjectMapper mapper;
    private final FindAccountUseCase accounts;

    DictGatewayHttpAdapter(DictGatewayProperties props, CircuitBreakerRegistry registry, ObjectMapper mapper,
                           FindAccountUseCase accounts) {
        this.http = GatewayHttp.client(props.baseUrl(), props.connectTimeout(), props.readTimeout());
        this.breaker = registry.circuitBreaker(DEPENDENCY);
        this.mapper = mapper;
        this.accounts = accounts;
    }

    @Override
    public DictRegistration register(PixKey key) {
        AccountInfo account = accounts.findById(key.accountId())
                .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND, "Conta não encontrada."));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("keyType", key.value().type().name());
        body.put("key", key.value().value());
        body.put("account", Map.of("ispb", account.ispb(), "branch", account.branch(), "number", account.number(),
                "type", account.accountType()));
        body.put("holder", Map.of("documentType", account.document().type(), "document", account.document().value(),
                "name", account.holderName()));
        JsonNode json = post("/dict/v1/entries", key.id().toString(), body, false);
        return new DictRegistration(json.path("cid").asText(), Instant.parse(json.path("registeredAt").asText()));
    }

    @Override
    public void delete(PixKey key, DeletionReason reason) {
        post("/dict/v1/entries/deletion", key.id() + ":delete", Map.of("keyType", key.value().type().name(),
                "key", key.value().value(), "reason", reason.name()), false);
    }

    @Override
    public Optional<DictEntry> lookup(PixKeyValue value, Document requester) {
        JsonNode json = post("/dict/v1/entries/lookup", null, Map.of("keyType", value.type().name(), "key", value.value(),
                "requesterDocument", requester.value()), true);
        if (json == null) {
            return Optional.empty();
        }
        Document owner = Document.of(json.path("owner").path("document").asText());
        return Optional.of(new DictEntry(json.path("owner").path("name").asText(), owner, json.path("account").path("ispb").asText(),
                json.path("account").path("branch").asText(), json.path("account").path("number").asText(),
                json.path("account").path("type").asText(), Instant.parse(json.path("ownershipDate").asText())));
    }

    /** {@code notFoundIsEmpty}: 404 ⇒ {@code null} (lookup); nos demais, 404 é erro de negócio. */
    private JsonNode post(String path, String requestId, Map<String, Object> body, boolean notFoundIsEmpty) {
        return GatewayHttp.call(breaker, DEPENDENCY, () -> {
            RestClient.RequestBodySpec spec = http.post().uri(path).contentType(MediaType.APPLICATION_JSON);
            if (requestId != null) {
                spec = spec.header("X-Request-Id", requestId);
            }
            return spec.body(body).exchange((request, response) -> {
                int status = response.getStatusCode().value();
                JsonNode json = read(response.getBody());
                if (status == 404 && notFoundIsEmpty) {
                    return null;
                }
                GatewayHttp.failOnTechnicalStatus(status, DEPENDENCY);
                if (response.getStatusCode().is2xxSuccessful()) {
                    return json;
                }
                // 4xx definitivo: negócio (nunca retentado)
                throw new BusinessException(status == 409 ? ErrorCodes.CONFLICT : ErrorCodes.INVALID_PIX_KEY,
                        json.path("message").asText("DICT recusou a operação."));
            });
        });
    }

    private JsonNode read(InputStream stream) throws IOException {
        return stream == null ? mapper.createObjectNode() : mapper.readTree(stream);
    }
}
