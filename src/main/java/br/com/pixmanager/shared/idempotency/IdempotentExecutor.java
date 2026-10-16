package br.com.pixmanager.shared.idempotency;

import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.shared.exception.ConflictException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Envolve uma operação {@code POST} com {@code Idempotency-Key}: replay devolve a resposta ORIGINAL (inclusive uma resposta de
 * recusa de negócio, como o 422 de limite); corpo diferente ⇒ 422; em andamento ⇒ 409; exceção antes do commit ⇒ libera a chave.
 * {@code complete} acontece na MESMA transação do negócio, então o replay só existe se o commit ocorreu.
 */
@Component
public class IdempotentExecutor {

    private static final Duration LEASE = Duration.ofSeconds(60);
    private static final Duration TTL = Duration.ofHours(24);

    private final IdempotencyService service;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public IdempotentExecutor(IdempotencyService service, ObjectMapper mapper, PlatformTransactionManager txManager) {
        this.service = service;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(txManager);
    }

    /** O que a operação devolve: status HTTP + corpo (qualquer objeto serializável). */
    public record Response(int status, Object body) {}

    /** O que foi (ou será) respondido, já serializado; {@code replayed} indica que veio do armazenamento. */
    public record Result(int status, JsonNode body, boolean replayed) {}

    public Result execute(String clientId, String operation, String key, Object request, Supplier<Response> action) {
        IdempotencyOutcome outcome = service.begin(clientId, operation, key, toJson(request), LEASE, TTL);
        return switch (outcome) {
            case IdempotencyOutcome.Replay replay -> new Result(replay.status(), parse(replay.body()), true);
            case IdempotencyOutcome.Mismatch mismatch -> throw new BusinessException(ErrorCodes.IDEMPOTENCY_MISMATCH,
                    "A Idempotency-Key já foi usada com outro corpo de requisição.");
            case IdempotencyOutcome.InProgress inProgress -> throw new ConflictException(ErrorCodes.IDEMPOTENCY_IN_PROGRESS,
                    "Requisição idêntica em andamento. Tente novamente em instantes.");
            case IdempotencyOutcome.Proceed proceed -> {
                try {
                    Result result = tx.execute(status -> {
                        Response response = action.get();
                        String json = toJson(response.body());
                        service.complete(proceed.recordId(), response.status(), json);
                        return new Result(response.status(), parse(json), false);
                    });
                    yield result;
                } catch (RuntimeException e) {
                    try {
                        service.release(proceed.recordId());  // falha NÃO queima a chave
                    } catch (RuntimeException releaseFailure) {
                        e.addSuppressed(releaseFailure);      // a exceção original é a que importa ao chamador
                    }
                    throw e;
                }
            }
        };
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar corpo para idempotência", e);
        }
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Resposta idempotente armazenada ilegível", e);
        }
    }
}
