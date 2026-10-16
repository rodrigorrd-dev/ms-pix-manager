package br.com.pixmanager.shared.exception.handler;

import br.com.pixmanager.shared.properties.ErrorProperties;

import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.time.Instant;

/**
 * Fábrica ÚNICA de {@link ProblemDetail} (RFC 9457). Propriedades de extensão em INGLÊS ({@code code}, {@code errors},
 * {@code timestamp}, {@code traceId}): decisão de fio registrada em docs/11 — o mesa-credito-full misturava pt/en.
 */
public final class ProblemDetails {

    private ProblemDetails() {}

    public static ProblemDetail of(ErrorProperties props, HttpStatusCode status, String title, String detail, String code) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        pd.setType(URI.create(props.baseTypeUri() + code));
        pd.setProperty("code", code);
        pd.setProperty("timestamp", Instant.now().toString());
        String traceId = MDC.get("traceId");
        if (traceId != null) {
            pd.setProperty("traceId", traceId);
        }
        return pd;
    }
}
