package br.com.pixmanager.shared.exception.handler;

import br.com.pixmanager.shared.exception.BadRequestException;
import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.shared.exception.ClassifiedError;
import br.com.pixmanager.shared.exception.ConflictException;
import br.com.pixmanager.shared.exception.DependencyUnavailableException;
import br.com.pixmanager.shared.exception.ErrorCategory;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.properties.ErrorProperties;
import br.com.pixmanager.shared.exception.ExceptionClassifier;
import br.com.pixmanager.shared.exception.ForbiddenException;
import br.com.pixmanager.shared.exception.IntegrationException;
import br.com.pixmanager.shared.exception.InvalidTransitionException;
import br.com.pixmanager.shared.exception.NotFoundException;
import br.com.pixmanager.shared.exception.PreconditionFailedException;
import br.com.pixmanager.shared.exception.PreconditionRequiredException;
import br.com.pixmanager.shared.exception.TooManyRequestsException;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * Traduz exceções em {@code application/problem+json} (RFC 9457). Decisão, status e mensagem segura vêm do
 * {@link ExceptionClassifier}; aqui só se escolhe o título. 405/406/415/429 têm handler próprio (nunca caem no 500).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final MessageSource messages;
    private final ErrorProperties errorProperties;
    private final ExceptionClassifier classifier;

    public GlobalExceptionHandler(MessageSource messages, ErrorProperties errorProperties) {
        this.messages = messages;
        this.errorProperties = errorProperties;
        this.classifier = new ExceptionClassifier(messages);
    }

    @ExceptionHandler({BadRequestException.class, NotFoundException.class, ConflictException.class, ForbiddenException.class,
            InvalidTransitionException.class, PreconditionFailedException.class, PreconditionRequiredException.class,
            TooManyRequestsException.class, BusinessException.class, IntegrationException.class,
            org.springframework.security.access.AccessDeniedException.class,
            IllegalArgumentException.class, IllegalStateException.class})
    ResponseEntity<ProblemDetail> handleDomain(Exception exception) {
        return respond(exception, titleKeyFor(exception));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
        List<ProblemField> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> new ProblemField(fieldError.getField(), fieldError.getCode(), fieldError.getDefaultMessage()))
                .sorted(java.util.Comparator.comparing(ProblemField::field))
                .toList();
        ProblemDetail problemDetail = ProblemDetails.of(errorProperties, HttpStatus.BAD_REQUEST, msg("error.invalidPayload.title"),
                msg("error.validation.fields.detail"), ErrorCodes.INVALID_PAYLOAD);
        problemDetail.setProperty("errors", errors);
        return problemDetail;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail handleConstraint(ConstraintViolationException exception) {
        List<ProblemField> errors = exception.getConstraintViolations().stream()
                .map(violation -> new ProblemField(violation.getPropertyPath().toString(), null, violation.getMessage()))
                .sorted(java.util.Comparator.comparing(ProblemField::field))
                .toList();
        ProblemDetail problemDetail = ProblemDetails.of(errorProperties, HttpStatus.BAD_REQUEST, msg("error.invalidPayload.title"),
                msg("error.validation.params.detail"), ErrorCodes.INVALID_PAYLOAD);
        problemDetail.setProperty("errors", errors);
        return problemDetail;
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    ProblemDetail handleBadRequest(Exception exception) {
        log.warn("Requisição malformada: {}", exception.getClass().getSimpleName());
        return ProblemDetails.of(errorProperties, HttpStatus.BAD_REQUEST, msg("error.invalidRequest.title"),
                msg("error.invalidRequest.detail"), ErrorCodes.INVALID_PAYLOAD);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> handleMethodNotAllowed(HttpRequestMethodNotSupportedException exception) {
        ProblemDetail problemDetail = ProblemDetails.of(errorProperties, HttpStatus.METHOD_NOT_ALLOWED, msg("error.methodNotAllowed.title"),
                msg("error.methodNotAllowed.detail"), ErrorCodes.METHOD_NOT_ALLOWED);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (exception.getSupportedHttpMethods() != null) {
            builder.allow(exception.getSupportedHttpMethods().toArray(new org.springframework.http.HttpMethod[0]));
        }
        return builder.body(problemDetail);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ProblemDetail handleUnsupportedMediaType(HttpMediaTypeNotSupportedException exception) {
        return ProblemDetails.of(errorProperties, HttpStatus.UNSUPPORTED_MEDIA_TYPE, msg("error.unsupportedMediaType.title"),
                msg("error.unsupportedMediaType.detail"), ErrorCodes.UNSUPPORTED_MEDIA_TYPE);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<ProblemDetail> handleNotAcceptable(HttpMediaTypeNotAcceptableException exception) {
        ProblemDetail problemDetail = ProblemDetails.of(errorProperties, HttpStatus.NOT_ACCEPTABLE, msg("error.notAcceptable.title"),
                msg("error.notAcceptable.detail"), ErrorCodes.NOT_ACCEPTABLE);
        // sem Content-Type negociável: devolve JSON simples
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail handleNoResource(NoResourceFoundException exception) {
        return ProblemDetails.of(errorProperties, HttpStatus.NOT_FOUND, msg("error.notFound.title"),
                msg("error.notFound.detail"), ErrorCodes.NOT_FOUND);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleGeneric(Exception exception) {
        return respond(exception, ExceptionClassifier.isDatabaseUnavailable(exception) ? "error.unavailable.title" : "error.internal.title");
    }

    private ResponseEntity<ProblemDetail> respond(Throwable exception, String titleKey) {
        ClassifiedError error = classifier.classify(exception);
        error.logTo(log, null);
        ProblemDetail problemDetail = ProblemDetails.of(errorProperties, HttpStatus.valueOf(error.category().httpStatus()),
                msg(titleKey), error.message(), error.code());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(error.category().httpStatus());
        if (error.category() == ErrorCategory.SERVICE_UNAVAILABLE || error.category() == ErrorCategory.TOO_MANY_REQUESTS) {
            long seconds = exception instanceof TooManyRequestsException tooMany ? Math.max(1, tooMany.getRetryAfter().toSeconds()) : 5;
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds));
        }
        return builder.body(problemDetail);
    }

    private static String titleKeyFor(Throwable error) {
        return switch (error) {
            case BadRequestException exception -> "error.invalidRequest.title";
            case NotFoundException exception -> "error.notFound.title";
            case PreconditionFailedException exception -> "error.preconditionFailed.title";
            case PreconditionRequiredException exception -> "error.preconditionRequired.title";
            case TooManyRequestsException exception -> "error.tooManyRequests.title";
            case ConflictException exception -> "error.conflict.title";
            case ForbiddenException exception -> "error.accessDenied.title";
            case org.springframework.security.access.AccessDeniedException exception -> "error.accessDenied.title";
            case InvalidTransitionException exception -> "error.invalidTransition.title";
            case DependencyUnavailableException exception -> "error.unavailable.title";
            case IntegrationException exception -> "error.integration.title";
            case BusinessException exception -> "error.business.title";
            case IllegalArgumentException exception -> "error.invalidRequest.title";
            case br.com.pixmanager.shared.exception.CryptoException exception -> "error.internal.title";
            case IllegalStateException exception -> "error.inconsistentState.title";
            default -> "error.internal.title";
        };
    }

    private String msg(String key) {
        return messages.getMessage(key, null, key, LocaleContextHolder.getLocale());
    }

    /** Um item de {@code errors[]} no Problem Detail. */
    public record ProblemField(String field, String code, String message) {}
}
