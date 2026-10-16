package br.com.pixmanager.shared.exception;

import org.slf4j.Logger;

/**
 * Resultado de classificar uma exceção: o que devolver ao cliente (mensagem já <b>segura</b>) e o que registrar em log.
 * O log fica com o adaptador de entrada ({@link #logTo}); só a decisão de <i>o que</i> e <i>em que nível</i> é central.
 */
public record ClassifiedError(ErrorCategory category, String code, String message, Diagnostic diagnostic) {

    public enum Severity { WARN, ERROR }

    public record Diagnostic(Severity severity, String description, Throwable cause) {}

    static ClassifiedError of(ErrorCategory category, String code, String message) {
        return new ClassifiedError(category, code, message, null);
    }

    ClassifiedError logged(Severity severity, String description, Throwable cause) {
        return new ClassifiedError(category, code, message, new Diagnostic(severity, description, cause));
    }

    public void logTo(Logger log, String context) {
        if (diagnostic == null) {
            return;
        }
        String text = context == null || context.isBlank()
                ? diagnostic.description()
                : diagnostic.description() + " (" + context + ")";
        if (diagnostic.severity() == Severity.ERROR) {
            log.error("{}", text, diagnostic.cause());
        } else {
            log.warn("{}", text, diagnostic.cause());
        }
    }
}
