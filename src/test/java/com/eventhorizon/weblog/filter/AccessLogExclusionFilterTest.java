package com.eventhorizon.weblog.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.lang.reflect.Method;
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

    // ── helper ────────────────────────────────────────────────────────────────

    private static String resolveIp(MockHttpServletRequest req) throws Exception {
        Method m = AccessLogExclusionFilter.class
                .getDeclaredMethod("resolveIp", HttpServletRequest.class);
        m.setAccessible(true);
        return (String) m.invoke(null, req);
    }
}
