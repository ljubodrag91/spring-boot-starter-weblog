package com.eventhorizon.weblog.filter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Method;
import java.util.List;
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
 *   <li>Context-path stripping — full URI {@code /myapp/admin/logs} maps to path
 *       {@code /admin/logs} and is correctly excluded</li>
 *   <li>{@code resolveIp} — XFF → X-Real-IP → remoteAddr priority chain</li>
 * </ul>
 */
class AccessLogExclusionFilterTest {

    private Filter filter;
    private AccessLogExclusionFilter config;
    private ListAppender<ILoggingEvent> exclusionsAppender;

    @BeforeEach
    void setUp() {
        config = new AccessLogExclusionFilter(new WebLogProperties());
        filter = config.accessLogExclusionFilter().getFilter();

        // Attach an in-memory appender so we can inspect the JSON line emitted by the filter
        // without writing to disk.
        exclusionsAppender = new ListAppender<>();
        exclusionsAppender.start();
        Logger exclLog = (Logger) LoggerFactory.getLogger(AccessLogExclusionFilter.EXCLUSIONS_LOGGER_NAME);
        exclLog.addAppender(exclusionsAppender);
    }

    @AfterEach
    void tearDown() {
        Logger exclLog = (Logger) LoggerFactory.getLogger(AccessLogExclusionFilter.EXCLUSIONS_LOGGER_NAME);
        exclLog.detachAppender(exclusionsAppender);
        // Always clear the SecurityContext — tests that populate it must not leak into siblings.
        SecurityContextHolder.clearContext();
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

    // ── user field — pulled from request attribute, written to JSON ───────────

    @Test
    void excludedPath_withUserAttribute_includesUserFieldInJson() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/admin/logs");
        req.setAttribute(AccessLogExclusionFilter.USER_REQ_ATTR, "user@example.com");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, (r, s) -> { /* no-op chain */ });

        String json = lastExclusionJson();
        assertThat(json)
                .as("user field should appear in the exclusion JSON when the request attribute is set")
                .contains("\"user\":\"user@example.com\"");
    }

    @Test
    void excludedPath_withoutUserAttribute_omitsUserField() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/admin/logs");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, (r, s) -> { /* no-op chain */ });

        assertThat(lastExclusionJson())
                .as("user field should be omitted when no request attribute is set")
                .doesNotContain("\"user\"");
    }

    @Test
    void excludedPath_userWithQuoteAndBackslash_escapedForJson() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/admin/logs");
        // Backslash and double-quote both need JSON escaping. If the filter forgets either,
        // the resulting line is no longer parseable as JSON.
        req.setAttribute(AccessLogExclusionFilter.USER_REQ_ATTR, "weird\\\"name");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, (r, s) -> { /* no-op chain */ });

        String json = lastExclusionJson();
        assertThat(json).contains("\"user\":\"weird\\\\\\\"name\"");
    }

    // ── principalCaptureFilter — reads SecurityContextHolder into request attribute ─

    @Test
    void principalCaptureFilter_authenticatedUser_setsRequestAttribute() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice@example.com", "pw",
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        Filter capture = config.principalCaptureFilter().getFilter();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/anything");
        MockHttpServletResponse res = new MockHttpServletResponse();

        capture.doFilter(req, res, (r, s) -> { /* no-op chain */ });

        assertThat(req.getAttribute(AccessLogExclusionFilter.USER_REQ_ATTR))
                .as("principal capture filter should publish the authenticated user as a request attribute")
                .isEqualTo("alice@example.com");
    }

    @Test
    void principalCaptureFilter_anonymousAuthentication_doesNotSetAttribute() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser",
                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        Filter capture = config.principalCaptureFilter().getFilter();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/anything");
        MockHttpServletResponse res = new MockHttpServletResponse();

        capture.doFilter(req, res, (r, s) -> { /* no-op chain */ });

        assertThat(req.getAttribute(AccessLogExclusionFilter.USER_REQ_ATTR))
                .as("anonymous principal must be treated as no user — it is noise in exclusion logs")
                .isNull();
    }

    @Test
    void principalCaptureFilter_noAuthentication_doesNotSetAttribute() throws Exception {
        // SecurityContext is cleared via @AfterEach — leave it empty here.
        Filter capture = config.principalCaptureFilter().getFilter();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/anything");
        MockHttpServletResponse res = new MockHttpServletResponse();

        capture.doFilter(req, res, (r, s) -> { /* no-op chain */ });

        assertThat(req.getAttribute(AccessLogExclusionFilter.USER_REQ_ATTR)).isNull();
    }

    // ── helper ────────────────────────────────────────────────────────────────

    /** Returns the last formatted JSON message captured by the exclusion logger. */
    private String lastExclusionJson() {
        assertThat(exclusionsAppender.list)
                .as("exclusion logger should have emitted exactly one line")
                .isNotEmpty();
        return exclusionsAppender.list.get(exclusionsAppender.list.size() - 1).getFormattedMessage();
    }

    private static String resolveIp(MockHttpServletRequest req) throws Exception {
        Method m = AccessLogExclusionFilter.class
                .getDeclaredMethod("resolveIp", HttpServletRequest.class);
        m.setAccessible(true);
        return (String) m.invoke(null, req);
    }
}
