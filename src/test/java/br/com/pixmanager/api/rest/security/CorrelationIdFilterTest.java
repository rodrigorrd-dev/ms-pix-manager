package br.com.pixmanager.api.rest.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void keepsSafeIncomingIdAndClearsMdcAfterwards() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-ID", "abc-123_X.9");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> seen.set(MDC.get("correlationId"));

        filter.doFilter(request, response, chain);

        assertThat(seen).hasValue("abc-123_X.9");
        assertThat(response.getHeader("X-Request-ID")).isEqualTo("abc-123_X.9");
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void replacesUnsafeOrMissingIdWithUuid() throws Exception {
        for (String bad : new String[] {"x\nforged line", "a".repeat(65), null}) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            if (bad != null) {
                request.addHeader("X-Request-ID", bad);
            }
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> { });
            assertThat(response.getHeader("X-Request-ID")).matches("[0-9a-f-]{36}");
        }
    }
}
