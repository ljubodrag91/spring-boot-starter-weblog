package com.eventhorizon.weblog.filter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.eventhorizon.weblog.WebLogProperties;
import jakarta.servlet.Filter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BodyCaptureFilter}.
 *
 * <p>The filter is extracted from the {@link FilterRegistrationBean} and called
 * directly. The {@link BodyCaptureFilter#BODIES_LOGGER_NAME bodies logger} has
 * an in-memory {@link ListAppender} attached so the captured JSON line can be
 * asserted against without touching disk.
 *
 * <p>Covers:
 * <ul>
 *   <li>Plain request/response body capture (small JSON)</li>
 *   <li>Truncation when payload exceeds {@code maxBytes} (line carries {@code reqTruncated:true})</li>
 *   <li>Content-type skip: multipart/binary requests are not captured</li>
 *   <li>Redaction: configured keys replaced with {@code "***"}</li>
 *   <li>{@code copyBodyToResponse} runs so the client still receives the body</li>
 * </ul>
 */
class BodyCaptureFilterTest {

    private ListAppender<ILoggingEvent> bodiesAppender;

    @BeforeEach
    void setUp() {
        bodiesAppender = new ListAppender<>();
        bodiesAppender.start();
        Logger bodiesLog = (Logger) LoggerFactory.getLogger(BodyCaptureFilter.BODIES_LOGGER_NAME);
        bodiesLog.addAppender(bodiesAppender);
    }

    @AfterEach
    void tearDown() {
        Logger bodiesLog = (Logger) LoggerFactory.getLogger(BodyCaptureFilter.BODIES_LOGGER_NAME);
        bodiesLog.detachAppender(bodiesAppender);
    }

    // ── Capture (small JSON request + response) ───────────────────────────────

    @Test
    void smallJsonBody_capturedInBothDirections() throws Exception {
        Filter filter = filterWith(new WebLogProperties().getBody());

        MockHttpServletRequest req = jsonRequest("POST", "/api/echo", "{\"hello\":\"world\"}");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, (r, s) -> {
            // Drain the request body so ContentCachingRequestWrapper sees the bytes.
            r.getInputStream().readAllBytes();
            s.setContentType("application/json");
            s.getWriter().write("{\"ok\":true}");
        });

        String json = lastBodyJson();
        assertThat(json).contains("\"method\":\"POST\"");
        assertThat(json).contains("\"uri\":\"/api/echo\"");
        assertThat(json).contains("\"reqBody\":\"{\\\"hello\\\":\\\"world\\\"}\"");
        assertThat(json).contains("\"resBody\":\"{\\\"ok\\\":true}\"");
        assertThat(json).doesNotContain("\"reqTruncated\":true");
        assertThat(json).doesNotContain("\"resTruncated\":true");
    }

    // ── Truncation ────────────────────────────────────────────────────────────

    @Test
    void requestBodyOverMaxBytes_truncatedAndFlagged() throws Exception {
        WebLogProperties.Body cfg = new WebLogProperties.Body();
        cfg.setMaxBytes(16);
        Filter filter = filterWith(cfg);

        String body = "A".repeat(64);
        MockHttpServletRequest req = jsonRequest("POST", "/api/x", body);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, (r, s) -> r.getInputStream().readAllBytes());

        String json = lastBodyJson();
        assertThat(json)
                .as("oversize request body must be marked truncated so the viewer can show the cap was hit")
                .contains("\"reqTruncated\":true");
        // The reqBody substring carries only the first 16 bytes
        assertThat(json).contains("\"reqBody\":\"" + "A".repeat(16) + "\"");
    }

    // ── Content-type skip (binary / multipart) ────────────────────────────────

    @Test
    void multipartRequest_bodyNotCaptured_reqSkippedFlagSet() throws Exception {
        Filter filter = filterWith(new WebLogProperties().getBody());

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/upload");
        req.setContentType("multipart/form-data; boundary=----X");
        req.setContent("ignored — should never be read by capture".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, (r, s) -> {
            // Don't drain — the skip path must not depend on the chain consuming the body.
        });

        String json = lastBodyJson();
        assertThat(json).contains("\"reqSkipped\":true");
        assertThat(json).doesNotContain("\"reqBody\"");
    }

    // ── Redaction (password key replaced with ***) ────────────────────────────

    @Test
    void passwordKeyInJsonRequestBody_redactedBeforeLogging() throws Exception {
        Filter filter = filterWith(new WebLogProperties().getBody());

        String body = "{\"email\":\"u@example.com\",\"password\":\"hunter2\"}";
        MockHttpServletRequest req = jsonRequest("POST", "/api/login", body);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, (r, s) -> r.getInputStream().readAllBytes());

        String json = lastBodyJson();
        assertThat(json)
                .as("plaintext password must NOT be written to the bodies log")
                .doesNotContain("hunter2");
        assertThat(json).contains("\\\"password\\\":\\\"***\\\"");
        // Email is not in the redact list and should pass through
        assertThat(json).contains("u@example.com");
    }

    @Test
    void customRedactKey_appliesToResponseBody() throws Exception {
        WebLogProperties.Body cfg = new WebLogProperties.Body();
        // Override the default list to include a domain-specific secret key.
        cfg.setRedactKeys(List.of("sessionToken"));
        Filter filter = filterWith(cfg);

        MockHttpServletRequest req = jsonRequest("GET", "/api/whoami", "");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, (r, s) -> {
            s.setContentType("application/json");
            s.getWriter().write("{\"sessionToken\":\"abc-very-secret\",\"plain\":\"x\"}");
        });

        String json = lastBodyJson();
        assertThat(json).doesNotContain("abc-very-secret");
        assertThat(json).contains("\\\"sessionToken\\\":\\\"***\\\"");
    }

    // ── copyBodyToResponse — without it the client gets an empty body ─────────

    @Test
    void responseBody_stillDeliveredToClient_afterCapture() throws Exception {
        Filter filter = filterWith(new WebLogProperties().getBody());

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/echo");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, (r, s) -> {
            s.setContentType("application/json");
            s.getWriter().write("{\"ok\":true}");
        });

        // ContentCachingResponseWrapper buffers the body — copyBodyToResponse is what
        // actually flushes it back to the real (mock) response. If we forget that
        // call in the filter, this assertion fails — guards against silent regressions
        // that would break every endpoint in a consumer app.
        assertThat(res.getContentAsString()).isEqualTo("{\"ok\":true}");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Filter filterWith(WebLogProperties.Body bodyCfg) {
        WebLogProperties props = new WebLogProperties();
        // Mirror the supplied body config into the WebLogProperties used by the filter.
        WebLogProperties.Body target = props.getBody();
        target.setEnabled(true);
        target.setMaxBytes(bodyCfg.getMaxBytes());
        target.setRedactKeys(bodyCfg.getRedactKeys());
        target.setSkipContentTypes(bodyCfg.getSkipContentTypes());

        FilterRegistrationBean<?> reg = new BodyCaptureFilter().bodyCaptureFilter(props);
        return (Filter) reg.getFilter();
    }

    private static MockHttpServletRequest jsonRequest(String method, String uri, String body) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.setContentType("application/json");
        req.setContent(body.getBytes(StandardCharsets.UTF_8));
        return req;
    }

    private String lastBodyJson() {
        assertThat(bodiesAppender.list)
                .as("bodies logger should have emitted exactly one line")
                .isNotEmpty();
        return bodiesAppender.list.get(bodiesAppender.list.size() - 1).getFormattedMessage();
    }
}
