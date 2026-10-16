package br.com.pixmanager.shared.exception;

import br.com.pixmanager.shared.constant.ErrorCodes;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;

import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("ExceptionClassifier — tabela única exceção → categoria + código + mensagem segura")
class ExceptionClassifierTest {

    @Mock Logger log;

    ExceptionClassifier classifier;

    @BeforeEach
    void setUp() {
        // sem chaves cadastradas, getMessage devolve a própria chave (default) ⇒ dá para afirmar qual mensagem foi escolhida
        classifier = new ExceptionClassifier(new StaticMessageSource());
    }

    @ParameterizedTest(name = "{0} ⇒ {1} / {2}")
    @MethodSource("classifications")
    void shouldClassifyEachExceptionIntoItsCategoryAndCode(Throwable cause, ErrorCategory category, String code) {
        ClassifiedError error = classifier.classify(cause);

        assertThat(error.category()).isEqualTo(category);
        assertThat(error.code()).isEqualTo(code);
    }

    static Stream<Arguments> classifications() {
        return Stream.of(
                Arguments.of(new NotFoundException("PIX-404-001", "x"), ErrorCategory.NOT_FOUND, "PIX-404-001"),
                Arguments.of(new ConflictException("PIX-409-021", "x"), ErrorCategory.CONFLICT, "PIX-409-021"),
                Arguments.of(new BadRequestException("PIX-400-001", "x"), ErrorCategory.BAD_REQUEST, "PIX-400-001"),
                Arguments.of(new InvalidTransitionException("PIX-422-000", "x"), ErrorCategory.UNPROCESSABLE_ENTITY, "PIX-422-000"),
                Arguments.of(new BusinessException("PIX-422-021", "x"), ErrorCategory.UNPROCESSABLE_ENTITY, "PIX-422-021"),
                Arguments.of(new TooManyRequestsException("PIX-429-002", "x", java.time.Duration.ofSeconds(30)), ErrorCategory.TOO_MANY_REQUESTS, "PIX-429-002"),
                Arguments.of(new ForbiddenException("PIX-403-002", "x"), ErrorCategory.FORBIDDEN, "PIX-403-002"),
                Arguments.of(new AccessDeniedException("x"), ErrorCategory.FORBIDDEN, ErrorCodes.ACCESS_DENIED),
                Arguments.of(new IntegrationException("PIX-502-001", "x", null), ErrorCategory.BAD_GATEWAY, "PIX-502-001"),
                Arguments.of(new ConstraintViolationException(Set.of()), ErrorCategory.BAD_REQUEST, ErrorCodes.INVALID_PAYLOAD),
                Arguments.of(new IllegalArgumentException("x"), ErrorCategory.BAD_REQUEST, ErrorCodes.INVALID_PAYLOAD),
                Arguments.of(new IllegalStateException("x"), ErrorCategory.UNPROCESSABLE_ENTITY, ErrorCodes.BUSINESS_RULE),
                Arguments.of(new org.springframework.transaction.CannotCreateTransactionException("pool"), ErrorCategory.SERVICE_UNAVAILABLE, ErrorCodes.DEPENDENCY_UNAVAILABLE),
                Arguments.of(new org.springframework.jdbc.CannotGetJdbcConnectionException("sem conexão"), ErrorCategory.SERVICE_UNAVAILABLE, ErrorCodes.DEPENDENCY_UNAVAILABLE),
                Arguments.of(new RuntimeException("embrulho", new org.springframework.dao.DataAccessResourceFailureException("caiu")), ErrorCategory.SERVICE_UNAVAILABLE, ErrorCodes.DEPENDENCY_UNAVAILABLE),
                Arguments.of(new RuntimeException("x"), ErrorCategory.INTERNAL_ERROR, ErrorCodes.INTERNAL_ERROR));
    }

    @Test
    @DisplayName("412 e 428 mantêm o código fixo das próprias exceções e a ordem dos case não as engole em BusinessException")
    void shouldClassifyPreconditionsBeforeTheirBusinessSuperclass() {
        assertThat(classifier.classify(new PreconditionFailedException("etag")).category()).isEqualTo(ErrorCategory.PRECONDITION_FAILED);
        assertThat(classifier.classify(new PreconditionRequiredException("if-match")).category())
                .isEqualTo(ErrorCategory.PRECONDITION_REQUIRED);
    }

    @Test
    @DisplayName("Dependência indisponível (503) vence a sua superclasse IntegrationException (502)")
    void shouldClassifyDependencyUnavailableAs503NotAs502() {
        ClassifiedError error = classifier.classify(new DependencyUnavailableException("core fora", null));

        assertThat(error.category()).isEqualTo(ErrorCategory.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("Mensagem ao cliente nunca vaza o detalhe interno de falhas de integração, acesso e erro inesperado")
    void shouldNotLeakInternalMessagesToTheClient() {
        assertThat(classifier.classify(new IntegrationException("PIX-502-001", "stacktrace do core", null)).message())
                .isEqualTo("error.integration.detail").doesNotContain("core");
        assertThat(classifier.classify(new ForbiddenException("PIX-403-002", "escopo cob.write ausente")).message())
                .isEqualTo("error.accessDenied.detail").doesNotContain("cob.write");
        assertThat(classifier.classify(new RuntimeException("senha=123")).message())
                .isEqualTo("error.internal.detail").doesNotContain("123");
    }

    @Test
    @DisplayName("Mensagem de negócio (404/409/422) é repassada: é segura por construção")
    void shouldPassBusinessMessagesThrough() {
        assertThat(classifier.classify(new NotFoundException("PIX-404-001", "Conta não encontrada.")).message())
                .isEqualTo("Conta não encontrada.");
    }

    @Test
    void shouldListFieldErrorsSortedForBindExceptions() {
        var bind = new BindException(new Object(), "request");
        bind.addError(new FieldError("request", "valor", "deve ser positivo"));
        bind.addError(new FieldError("request", "chave", "é obrigatória"));

        ClassifiedError error = classifier.classify(bind);

        assertThat(error.category()).isEqualTo(ErrorCategory.BAD_REQUEST);
        assertThat(error.message()).isEqualTo("chave: é obrigatória; valor: deve ser positivo");
        assertThat(error.diagnostic()).as("erro do cliente não gera log").isNull();
    }

    @Test
    void shouldFallBackToAGenericMessageWhenThereAreNoFieldErrors() {
        assertThat(classifier.classify(new BindException(new Object(), "request")).message()).isEqualTo("error.validation.fields.detail");
    }

    @Test
    @DisplayName("Erro inesperado é registrado em ERROR com a causa; erro de cliente não registra nada")
    void shouldLogUnexpectedErrorsAtErrorLevelWithTheCause() {
        var boom = new RuntimeException("boom");

        classifier.classify(boom).logTo(log, "GET /cob");
        classifier.classify(new NotFoundException("PIX-404-001", "x")).logTo(log, "GET /cob");

        verify(log).error(eq("{}"), eq("Erro não tratado: RuntimeException (GET /cob)"), eq(boom));
        verifyNoMoreInteractions(log);
    }

    @Test
    void shouldLogAccessDeniedAndUnavailableDependencyAtWarnLevel() {
        classifier.classify(new AccessDeniedException("negado")).logTo(log, null);
        classifier.classify(new DependencyUnavailableException("core fora", null)).logTo(log, " ");

        verify(log).warn("{}", "Acesso negado: negado", (Object) null);
        verify(log).warn(eq("{}"), argThat((Object text) -> text.toString().startsWith("Dependência indisponível")), (Object) isNull());
    }

    @ParameterizedTest
    @EnumSource(ErrorCategory.class)
    @DisplayName("isServerSide acompanha o status HTTP ≥ 500")
    void shouldFlagOnlyFiveHundredsAsServerSide(ErrorCategory category) {
        assertThat(category.isServerSide()).as("%s (%d)", category, category.httpStatus()).isEqualTo(category.httpStatus() >= 500);
    }
}
