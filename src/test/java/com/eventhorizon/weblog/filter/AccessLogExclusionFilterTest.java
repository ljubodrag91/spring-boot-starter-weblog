package com.eventhorizon.weblog.filter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import com.eventhorizon.weblog.WebLogProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AccessLogExclusionFilter}.
 *
 * The filter is extracted from the {@link org.springframework.boot.web.servlet.FilterRegistrationBean}
 * and invoked directly with {@link MockHttpServletRequest}/{@link MockHttpServletResponse} —
 * no Spring context needed.
 *
 * <p>Covers:
 * <ul>
 *   <li>Excluded paths → {@code skipLog} request attribute set (Tomcat suppression signal)</li>
 *   <li>Non-excluded paths → no {@code skipLog} attribute (normal access-log entry)</li>
 *   <li>Silent paths → {@code skipLog} set but no {@code exclusions.log} line; excluded-but-not-silent
 *       paths still get their audit line</li>
 *   <li>Context-path stripping — full URI {@code /myapp/admin/logs} maps to path
 *       {@code /admin/logs} and is correctly excluded</li>
 *   <li>{@code resolveIp} — XFF → X-Real-IP → remoteAddr priority chain</li>
 * </ul>
 */
class AccessLogExclusionFilterTest {

    private Filter filter;

    @BeforeEach
    void setUp() {
        filter = new AccessLogExclusionFilter(new WebLogProperties()).accessLogExclusionFilter().getFilter();
    }

    // ── excluded paths — skipLog attribute must be set ────────────────────────

    @ParameterizedTest(name = "path \"{0}\" is excluded → skipLog set")
    @ValueSource(strings = {
        "/admin/logs",
        "/admin/logs/data",
        "/swagger-ui/index.html",
        "/swagger-ui/",
        "/v3/api-docs",
        "/v3/api-docs/swagger-config"
    })
    void excludedPath_setsSkipLogAttribute(String path) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicBoolean skipLogSet = new AtomicBoolean(false);

        FilterChain chain = (r, s) -> skipLogSet.set(r.getAttribute("skipLog") != null);
        filter.doFilter(req, res, chain);

        assertThat(skipLogSet.get())
                .as("skipLog should be set for excluded path: %s", path)
                .isTrue();
    }

    // ── non-excluded paths — skipLog must NOT be set ──────────────────────────

    @ParameterizedTest(name = "path \"{0}\" is not excluded → no skipLog")
    @ValueSource(strings = {
        "/api/resource",
        "/api/list",
        "/api/items",
        "/admin/other",
        "/health"
    })
    void nonExcludedPath_skipLogAttributeNotSet(String path) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicBoolean skipLogSet = new AtomicBoolean(false);

        FilterChain chain = (r, s) -> skipLogSet.set(r.getAttribute("skipLog") != null);
        filter.doFilter(req, res, chain);

        assertThat(skipLogSet.get())
                .as("skipLog should NOT be set for non-excluded path: %s", path)
                .isFalse();
    }

    // ── context-path stripping ────────────────────────────────────────────────

    @Test
    void excludedPath_withContextPath_strippedBeforeMatching() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/myapp/admin/logs");
        req.setContextPath("/myapp");
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicBoolean skipLogSet = new AtomicBoolean(false);

        FilterChain chain = (r, s) -> skipLogSet.set(r.getAttribute("skipLog") != null);
        filter.doFilter(req, res, chain);

        assertThat(skipLogSet.get())
                .as("Context path /myapp should be stripped; /admin/logs is excluded")
                .isTrue();
    }

    @Test
    void nonExcludedPath_withContextPath_notMistakenlySuppressed() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/myapp/api/resource");
        req.setContextPath("/myapp");
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicBoolean skipLogSet = new AtomicBoolean(false);

        FilterChain chain = (r, s) -> skipLogSet.set(r.getAttribute("skipLog") != null);
        filter.doFilter(req, res, chain);

        assertThat(skipLogSet.get()).isFalse();
    }

    // ── silent paths — skipLog set, but NO exclusions.log line ────────────────

    @ParameterizedTest(name = "silent path \"{0}\" → skipLog set")
    @ValueSource(strings = {
        "/admin/logs/inflight",
        "/admin/logs/data",
        "/admin/logs/page",
        "/admin/logs/trace",
        "/admin/logs/log-viewer.css",
        "/admin/logs/log-viewer.js"
    })
    void silentPath_setsSkipLogAttribute(String path) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicBoolean skipLogSet = new AtomicBoolean(false);

        FilterChain chain = (r, s) -> skipLogSet.set(r.getAttribute("skipLog") != null);
        filter.doFilter(req, res, chain);

        assertThat(skipLogSet.get())
                .as("skipLog should be set for silent path: %s", path)
                .isTrue();
    }

    @ParameterizedTest(name = "silent path \"{0}\" → nothing written to exclusions.log")
    @ValueSource(strings = {
        "/admin/logs/inflight",
        "/admin/logs/data",
        "/admin/logs/log-viewer.css"
    })
    void silentPath_writesNoExclusionLine(String path) throws Exception {
        Logger exclLogger = (Logger) LoggerFactory.getLogger(
                AccessLogExclusionFilter.EXCLUSIONS_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        exclLogger.addAppender(appender);
        try {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
            MockHttpServletResponse res = new MockHttpServletResponse();

            filter.doFilter(req, res, (r, s) -> { });

            assertThat(appender.list)
                    .as("silent path must leave no exclusions.log line: %s", path)
                    .isEmpty();
        } finally {
            exclLogger.detachAppender(appender);
        }
    }

    @ParameterizedTest(name = "excluded-but-not-silent path \"{0}\" → one exclusions.log line")
    @ValueSource(strings = {
        "/admin/logs",          // bare viewer page — a real "admin opened the viewer" event
        "/swagger-ui/index.html",
        "/v3/api-docs"
    })
    void excludedNonSilentPath_writesExclusionLine(String path) throws Exception {
        Logger exclLogger = (Logger) LoggerFactory.getLogger(
                AccessLogExclusionFilter.EXCLUSIONS_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        exclLogger.addAppender(appender);
        try {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
            MockHttpServletResponse res = new MockHttpServletResponse();

            filter.doFilter(req, res, (r, s) -> { });

            assertThat(appender.list)
                    .as("excluded (non-silent) path must still be audited in exclusions.log: %s", path)
                    .hasSize(1);
        } finally {
            exclLogger.detachAppender(appender);
        }
    }

    // ── resolveIp — XFF → X-Real-IP → remoteAddr chain ──────────────────────

    @Test
    void resolveIp_xForwardedForPresent_firstValueUsed() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "203.0.113.5, 10.0.0.1");
        assertThat(resolveIp(req)).isEqualTo("203.0.113.5");
    }

    @Test
    void resolveIp_xForwardedForSingleValue_usedDirectly() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "198.51.100.1");
        assertThat(resolveIp(req)).isEqualTo("198.51.100.1");
    }

    @Test
    void resolveIp_noXff_xRealIpUsedAsFallback() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Real-IP", "192.168.1.100");
        assertThat(resolveIp(req)).isEqualTo("192.168.1.100");
    }

    @Test
    void resolveIp_noXffNoXRealIp_remoteAddrUsed() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("127.0.0.1");
        assertThat(resolveIp(req)).isEqualTo("127.0.0.1");
    }

    @Test
    void resolveIp_blankXff_fallsBackToRemoteAddr() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "   ");
        req.setRemoteAddr("10.10.0.1");
        assertThat(resolveIp(req)).isEqualTo("10.10.0.1");
    }

    // ── exclusions JSON — injection resistance ────────────────────────────────

    @Test
    void excludedPath_maliciousXForwardedFor_producesValidNonForgeableJson() throws Exception {
        // A crafted X-Forwarded-For (quotes + braces, no comma so it survives the
        // first-hop split) previously broke out of the hand-built JSON string. With
        // Jackson serialization it must stay a single, valid, escaped string value.
        String injection = "7.7.7.7\"}malicious{\"";

        Logger exclLogger = (Logger) LoggerFactory.getLogger(
                AccessLogExclusionFilter.EXCLUSIONS_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        exclLogger.addAppender(appender);
        try {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/admin/logs");
            req.addHeader("X-Forwarded-For", injection);
            MockHttpServletResponse res = new MockHttpServletResponse();

            filter.doFilter(req, res, (r, s) -> { });

            assertThat(appender.list).hasSize(1);
            String json = appender.list.get(0).getFormattedMessage();

            // Parses as one valid JSON object (would throw on the old concatenated output).
            JsonNode node = new ObjectMapper().readTree(json);
            // ip is the raw injected value, escaped into a single field — not structure.
            assertThat(node.get("ip").asText()).isEqualTo(injection);
            assertThat(node.get("uri").asText()).isEqualTo("/admin/logs"); // not forged
            assertThat(node.has("malicious")).isFalse();                    // no forged key
        } finally {
            exclLogger.detachAppender(appender);
        }
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private static String resolveIp(MockHttpServletRequest req) {
        // The per-request IP resolution now lives in the shared ClientIp helper (same package).
        return ClientIp.fromRequest(req);
    }
}
