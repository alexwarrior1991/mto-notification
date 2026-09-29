package com.alejandro.mtonotification.configuration.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** El filtro de correlación, sin contexto de Spring: es una pieza de servlet pura. */
class CorrelationIdFilterTest {

    private static final String HEADER = "X-Correlation-Id";

    private final CorrelationIdFilter filter = new CorrelationIdFilter(new CorrelationIdProperties(HEADER, 64));

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void generatesAnIdentifierWhenTheRequestDoesNotBringOne() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInMdc = new AtomicReference<>();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/notifications/inbox"), response,
                (request, res) -> seenInMdc.set(MDC.get(CorrelationIdFilter.MDC_KEY)));

        assertNotNull(seenInMdc.get());
        assertDoesNotThrow(() -> UUID.fromString(seenInMdc.get()));
        assertEquals(seenInMdc.get(), response.getHeader(HEADER));
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY), "El MDC se limpia al terminar: los hilos se reutilizan");
    }

    @Test
    void reusesAValidInboundIdentifier() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/notifications/inbox");
        request.addHeader(HEADER, "trace-42_ABC.9");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInMdc = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seenInMdc.set(MDC.get(CorrelationIdFilter.MDC_KEY)));

        assertEquals("trace-42_ABC.9", seenInMdc.get());
        assertEquals("trace-42_ABC.9", response.getHeader(HEADER));
    }

    @Test
    void replacesAnIdentifierThatCouldForgeLogLinesOrIsTooLong() throws Exception {
        for (String hostile : new String[]{"abc\r\nX-Injected: yes", "with space", "x".repeat(65)}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/notifications/inbox");
            request.addHeader(HEADER, hostile);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, (req, res) -> { });

            assertNotEquals(hostile, response.getHeader(HEADER));
            assertDoesNotThrow(() -> UUID.fromString(response.getHeader(HEADER)));
        }
    }
}
