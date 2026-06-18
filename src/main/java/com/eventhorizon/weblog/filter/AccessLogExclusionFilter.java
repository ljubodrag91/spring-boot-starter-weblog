package com.eventhorizon.weblog.filter;

import com.eventhorizon.weblog.WebLogProperties;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Two responsibilities:
 *
 * <ol>
 *   <li>Sets the {@code skipLog} request attribute on excluded paths so
 *       Tomcat's native access log valve skips them
 *       ({@code server.tomcat.accesslog.condition-unless=skipLog}).</li>
 *   <li>Writes a compact JSON line per excluded request to the
 *       {@value #EXCLUSIONS_LOGGER_NAME} logger, which Logback routes
 *       to {@code logs/exclusions.log}. The log viewer serves this as
 *       the "Excluded" tab.</li>
 * </ol>
 *
 * <p>Runs at {@link Ordered#HIGHEST_PRECEDENCE}{@code  + 1} — after
 * {@link RequestIdFilter} ({@code HIGHEST_PRECEDENCE}) so that the
 * {@code X-Request-Id} response header is already set when this filter's
 * {@code finally} block fires, allowing {@code requestId} to be included
 * in the exclusion log JSON.
 *
 * <p>No response-wrapper is needed: {@link HttpServletResponse#getStatus()}
 * is readable after {@code chain.doFilter()} returns.
 *
 * <h2>Excluded prefixes</h2>
 * Controlled by {@link WebLogProperties#getExcludedPrefixes()}.
 * Default: {@code /admin/logs}, {@code /swagger-ui}, {@code /v3/api-docs}, {@code /actuator}.
 * Override via {@code log-viewer.excluded-prefixes} in {@code application.properties}.
 *
 * <h2>Authenticated user</h2>
 * When Spring Security is present, a companion filter at
 * {@link Ordered#LOWEST_PRECEDENCE} ({@link #principalCaptureFilter()}) records the
 * authenticated principal's name into {@link #USER_REQ_ATTR}. This filter's
 * {@code finally} block reads that attribute and includes a {@code "user"} field
 * in the exclusions JSON. The capture filter is necessary because
 * {@code SecurityContextHolder} is cleared by Spring Security in its own
 * {@code finally} (which runs <em>before</em> this filter's {@code finally}
 * since this filter is outermost).
 */
@Configuration
@RequiredArgsConstructor
public class AccessLogExclusionFilter {

    /**
     * Logger name used by this filter — must match the {@code <logger name="...">}
     * in {@code logback-weblog-include.xml} so that exclusion lines are routed to
     * {@code logs/exclusions.log} and kept out of the root console appender.
     */
    public static final String EXCLUSIONS_LOGGER_NAME = "com.eventhorizon.weblog.exclusions";

    /** Request attribute key set by {@link #principalCaptureFilter()}. */
    public static final String USER_REQ_ATTR = "com.eventhorizon.weblog.user";

    private static final Logger EXCL_LOG = LoggerFactory.getLogger(EXCLUSIONS_LOGGER_NAME);

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final WebLogProperties properties;

    @Bean
    public FilterRegistrationBean<Filter> accessLogExclusionFilter() {
        List<String> excluded = properties.getExcludedPrefixes();

        FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>();
        reg.setFilter((ServletRequest req, ServletResponse res, FilterChain chain) -> {
            if (!(req instanceof HttpServletRequest http)) {
                chain.doFilter(req, res);
                return;
            }

            String uri  = http.getRequestURI();
            String ctx  = http.getContextPath();
            String path = (ctx != null && uri.startsWith(ctx))
                    ? uri.substring(ctx.length()) : uri;

            boolean isExcluded = false;
            for (String prefix : excluded) {
                if (path.startsWith(prefix)) { isExcluded = true; break; }
            }

            if (!isExcluded) {
                chain.doFilter(req, res);
                return;
            }

            // Suppress from Tomcat's native access log
            req.setAttribute("skipLog", Boolean.TRUE);

            long start = System.currentTimeMillis();
            try {
                chain.doFilter(req, res);
            } finally {
                long   dur    = System.currentTimeMillis() - start;
                int    status = (res instanceof HttpServletResponse hres) ? hres.getStatus() : 0;
                String ip     = resolveIp(http);
                String ts     = LocalDateTime.now().format(TS_FMT);
                // X-Request-Id was set on the response by RequestIdFilter (which runs first).
                String reqId  = (res instanceof HttpServletResponse hres)
                        ? hres.getHeader(RequestIdFilter.HEADER) : null;
                Object userAttr = http.getAttribute(USER_REQ_ATTR);
                String user = userAttr instanceof String s && !s.isBlank() ? s : null;
                // Escape backslash and double-quote so the URI/user stay valid JSON.
                String safeUri  = jsonEscape(uri);
                String safeUser = user != null ? jsonEscape(user) : null;
                String json = "{\"ts\":\"" + ts + "\""
                        + ",\"method\":\"" + http.getMethod() + "\""
                        + ",\"uri\":\"" + safeUri + "\""
                        + ",\"status\":" + status
                        + ",\"durationMs\":" + dur
                        + ",\"ip\":\"" + ip + "\""
                        + (reqId    != null ? ",\"requestId\":\"" + reqId + "\""  : "")
                        + (safeUser != null ? ",\"user\":\"" + safeUser + "\""    : "")
                        + "}";
                EXCL_LOG.info("{}", json);
            }
        });
        reg.addUrlPatterns("/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        reg.setName("accessLogExclusionFilter");
        return reg;
    }

    /**
     * Companion filter that captures the authenticated principal's name (e.g.
     * email/username) into request attribute {@link #USER_REQ_ATTR}. Registered at
     * {@link Ordered#LOWEST_PRECEDENCE} so it sits <em>inside</em> Spring Security's
     * filter chain — when its {@code finally} runs, the {@code SecurityContextHolder}
     * still holds the request's authentication (Spring Security clears it in its
     * own outer {@code finally}, which runs only after this filter has returned).
     *
     * <p>The filter is wired unconditionally; if Spring Security is not on the
     * runtime classpath the reflection lookup fails fast and the filter becomes a
     * no-op. No bean conditional is needed.
     */
    @Bean
    public FilterRegistrationBean<Filter> principalCaptureFilter() {
        FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>();
        reg.setFilter((ServletRequest req, ServletResponse res, FilterChain chain) -> {
            try {
                chain.doFilter(req, res);
            } finally {
                if (req instanceof HttpServletRequest http
                        && http.getAttribute(USER_REQ_ATTR) == null) {
                    String name = resolveCurrentUserName();
                    if (name != null) http.setAttribute(USER_REQ_ATTR, name);
                }
            }
        });
        reg.addUrlPatterns("/*");
        reg.setOrder(Ordered.LOWEST_PRECEDENCE);
        reg.setName("principalCaptureFilter");
        return reg;
    }

    private static String resolveIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        String xri = req.getHeader("X-Real-IP");
        if (xri != null && !xri.isBlank()) return xri;
        return req.getRemoteAddr();
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Spring Security principal lookup via reflection.
    //
    // Spring Security is declared <optional> in the starter's pom, so it may be
    // absent on the consumer's classpath. Reflection lets the filter degrade
    // gracefully to a no-op rather than crashing the whole filter chain with a
    // NoClassDefFoundError at startup.
    // ─────────────────────────────────────────────────────────────────────

    private static volatile boolean LOOKUP_INITIALIZED = false;
    private static volatile Method GET_CONTEXT;       // SecurityContextHolder.getContext()
    private static volatile Method GET_AUTHENTICATION; // SecurityContext.getAuthentication()
    private static volatile Method IS_AUTHENTICATED;   // Authentication.isAuthenticated()
    private static volatile Method GET_NAME;           // Authentication.getName()

    static String resolveCurrentUserName() {
        if (!LOOKUP_INITIALIZED) initLookup();
        if (GET_CONTEXT == null) return null;
        try {
            Object ctx = GET_CONTEXT.invoke(null);
            if (ctx == null) return null;
            Object auth = GET_AUTHENTICATION.invoke(ctx);
            if (auth == null) return null;
            Object authenticated = IS_AUTHENTICATED.invoke(auth);
            if (!Boolean.TRUE.equals(authenticated)) return null;
            Object name = GET_NAME.invoke(auth);
            if (name == null) return null;
            String s = name.toString();
            // Spring Security's default anonymous filter populates "anonymousUser" — treat as no user.
            if (s.isBlank() || "anonymousUser".equals(s)) return null;
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    private static synchronized void initLookup() {
        if (LOOKUP_INITIALIZED) return;
        try {
            Class<?> holder = Class.forName("org.springframework.security.core.context.SecurityContextHolder");
            Class<?> ctx    = Class.forName("org.springframework.security.core.context.SecurityContext");
            Class<?> auth   = Class.forName("org.springframework.security.core.Authentication");
            GET_CONTEXT        = holder.getMethod("getContext");
            GET_AUTHENTICATION = ctx.getMethod("getAuthentication");
            IS_AUTHENTICATED   = auth.getMethod("isAuthenticated");
            GET_NAME           = auth.getMethod("getName");
        } catch (Throwable t) {
            GET_CONTEXT = null;
        } finally {
            LOOKUP_INITIALIZED = true;
        }
    }
}
