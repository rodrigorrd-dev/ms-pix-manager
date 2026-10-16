package br.com.pixmanager.infrastructure.metrics;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Extrai o W3C {@code traceparent} do span corrente para persistir na outbox (trace único HTTP → DB → Kafka → consumer). */
@Component
public class TraceContextSupport {

    private final ObjectProvider<Tracer> tracer;

    public TraceContextSupport(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    /** {@code 00-<traceId>-<spanId>-01} ou {@code null} sem span ativo. */
    public String currentTraceParent() {
        Tracer t = tracer.getIfAvailable();
        if (t == null) {
            return null;
        }
        Span span = t.currentSpan();
        if (span == null) {
            return null;
        }
        String traceId = span.context().traceId();
        String padded = "0".repeat(Math.max(0, 32 - traceId.length())) + traceId;
        return "00-" + padded + "-" + span.context().spanId() + "-01";
    }

    public String currentTraceId() {
        Tracer t = tracer.getIfAvailable();
        Span span = t == null ? null : t.currentSpan();
        return span == null ? null : span.context().traceId();
    }
}
