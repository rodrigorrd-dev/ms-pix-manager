package br.com.pixmanager.shared.exception;

import br.com.pixmanager.shared.constant.ErrorCodes;

import jakarta.validation.ConstraintViolationException;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;

import java.util.stream.Collectors;

import static br.com.pixmanager.shared.exception.ClassifiedError.Severity.ERROR;
import static br.com.pixmanager.shared.exception.ClassifiedError.Severity.WARN;

/**
 * A única tabela "exceção → categoria + código + mensagem segura" do serviço (adaptada do mesa-credito-full).
 * A ordem dos {@code case} importa: subclasses antes da superclasse (o compilador checa a dominância).
 */
public class ExceptionClassifier {

    private final MessageSource messages;

    public ExceptionClassifier(MessageSource messages) {
        this.messages = messages;
    }

    public ClassifiedError classify(Throwable cause) {
        return switch (cause) {
            case NotFoundException notFound ->
                    ClassifiedError.of(ErrorCategory.NOT_FOUND, notFound.getCode(), notFound.getMessage());
            case PreconditionFailedException failed ->
                    ClassifiedError.of(ErrorCategory.PRECONDITION_FAILED, failed.getCode(), failed.getMessage());
            case PreconditionRequiredException required ->
                    ClassifiedError.of(ErrorCategory.PRECONDITION_REQUIRED, required.getCode(), required.getMessage());
            case ConflictException conflict ->
                    ClassifiedError.of(ErrorCategory.CONFLICT, conflict.getCode(), conflict.getMessage());
            case BadRequestException badRequest ->
                    ClassifiedError.of(ErrorCategory.BAD_REQUEST, badRequest.getCode(), badRequest.getMessage());
            case ForbiddenException forbidden ->
                    ClassifiedError.of(ErrorCategory.FORBIDDEN, forbidden.getCode(), msg("error.accessDenied.detail"))
                            .logged(WARN, "Acesso negado [%s]: %s".formatted(forbidden.getCode(), forbidden.getMessage()), null);
            case AccessDeniedException denied ->
                    ClassifiedError.of(ErrorCategory.FORBIDDEN, ErrorCodes.ACCESS_DENIED, msg("error.accessDenied.detail"))
                            .logged(WARN, "Acesso negado: " + denied.getMessage(), null);
            case InvalidTransitionException transition ->
                    ClassifiedError.of(ErrorCategory.UNPROCESSABLE_ENTITY, transition.getCode(), transition.getMessage());
            case TooManyRequestsException tooMany ->
                    ClassifiedError.of(ErrorCategory.TOO_MANY_REQUESTS, tooMany.getCode(), tooMany.getMessage());
            case BusinessException business ->
                    ClassifiedError.of(ErrorCategory.UNPROCESSABLE_ENTITY, business.getCode(), business.getMessage());
            case DependencyUnavailableException unavailable ->
                    ClassifiedError.of(ErrorCategory.SERVICE_UNAVAILABLE, unavailable.getCode(), msg("error.unavailable.detail"))
                            .logged(WARN, "Dependência indisponível [%s]: %s".formatted(unavailable.getCode(), unavailable.getMessage()),
                                    null);
            case IntegrationException integration ->
                    ClassifiedError.of(ErrorCategory.BAD_GATEWAY, integration.getCode(), msg("error.integration.detail"))
                            .logged(ERROR, "Falha de integração [%s]: %s".formatted(integration.getCode(), integration.getMessage()),
                                    integration);
            case ConstraintViolationException violation ->
                    ClassifiedError.of(ErrorCategory.BAD_REQUEST, ErrorCodes.INVALID_PAYLOAD, msg("error.validation.params.detail"))
                            .logged(WARN, "Violação de restrição: " + violation.getMessage(), null);
            case BindException invalid ->
                    ClassifiedError.of(ErrorCategory.BAD_REQUEST, ErrorCodes.INVALID_PAYLOAD, describe(invalid));
            case IllegalArgumentException illegalArgument ->
                    ClassifiedError.of(ErrorCategory.BAD_REQUEST, ErrorCodes.INVALID_PAYLOAD, msg("error.invalidRequest.detail"))
                            .logged(WARN, "Argumento inválido: " + illegalArgument.getMessage(), null);
            case CryptoException crypto ->
                    ClassifiedError.of(ErrorCategory.INTERNAL_ERROR, ErrorCodes.INTERNAL_ERROR, msg("error.internal.detail"))
                            .logged(ERROR, "Falha de criptografia: " + crypto.getMessage(), crypto);
            case IllegalStateException illegalState ->
                    ClassifiedError.of(ErrorCategory.UNPROCESSABLE_ENTITY, ErrorCodes.BUSINESS_RULE, msg("error.inconsistentState.detail"))
                            .logged(WARN, "Estado inconsistente: " + illegalState.getMessage(), illegalState);
            case Throwable down when isDatabaseUnavailable(down) ->
                    ClassifiedError.of(ErrorCategory.SERVICE_UNAVAILABLE, ErrorCodes.DEPENDENCY_UNAVAILABLE, msg("error.unavailable.detail"))
                            .logged(ERROR, "Banco de dados indisponível: " + down.getClass().getSimpleName(), null);
            default ->
                    ClassifiedError.of(ErrorCategory.INTERNAL_ERROR, ErrorCodes.INTERNAL_ERROR, msg("error.internal.detail"))
                            .logged(ERROR, "Erro não tratado: " + cause.getClass().getSimpleName(), cause);
        };
    }

    /**
     * Banco fora do ar / pool esgotado: falha TEMPORÁRIA de infraestrutura ⇒ 503 + Retry-After (o cliente e o balanceador sabem que podem
     * tentar de novo), e não 500 ("bug"). Percorre as causas porque o Spring/Hibernate embrulham a exceção de conexão.
     */
    public static boolean isDatabaseUnavailable(Throwable error) {
        for (Throwable exception = error; exception != null; exception = exception.getCause() == exception ? null : exception.getCause()) {
            if (exception instanceof org.springframework.transaction.CannotCreateTransactionException
                    || exception instanceof org.springframework.dao.DataAccessResourceFailureException
                    || exception instanceof org.springframework.dao.TransientDataAccessResourceException
                    || exception instanceof org.hibernate.exception.JDBCConnectionException) {
                return true;
            }
        }
        return false;
    }

    private String describe(BindException invalid) {
        String fieldErrors = invalid.getFieldErrors().stream()
                .map(field -> field.getField() + ": " + field.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return fieldErrors.isEmpty() ? msg("error.validation.fields.detail") : fieldErrors;
    }

    private String msg(String key) {
        return messages.getMessage(key, null, key, LocaleContextHolder.getLocale());
    }
}
