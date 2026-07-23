package com.eventhorizon.weblog.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * <h2>Excluded vs silent prefixes</h2>
 * Two tiers, both controlled by {@link WebLogProperties}:
 * <ul>
 *   <li><b>Excluded</b> ({@link WebLogProperties#getExcludedPrefixes()}, default {@code /admin/logs},
 *       {@code /swagger-ui}, {@code /v3/api-docs}, {@code /actuator}) — kept out of the Tomcat access log but still
 *       recorded as one JSON line in {@code exclusions.log} (an audit trail of tooling traffic).</li>
 *   <li><b>Silent</b> ({@link WebLogProperties#getSilentPrefixes()}) — suppressed from the access log
 *       <i>and</i> {@code exclusions.log}: recorded nowhere. This is for the viewer's own
 *       high-frequency self-polling (e.g. the {@code /admin/logs/inflight} live strip), which would
 *       otherwise flood the exclusions audit with its own ticks.</li>
 * </ul>
 * A silent match alone suppresses the access log (so the path need not also be excluded), and it is
 * checked first, so a path matching both tiers (e.g. {@code /admin/logs/data} under the
 * {@code /admin/logs} excluded prefix) is silenced rather than logged.
 * Override via {@code log-viewer.excluded-prefixes} / {@code log-viewer.silent-prefixes}.
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

    /** Diagnostics for this filter itself (not the exclusions data stream). */
    private static final Logger LOG = LoggerFactory.getLogger(AccessLogExclusionFilter.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final WebLogProperties properties;

    @Bean
    public FilterRegistrationBean<Filter> accessLogExclusionFilter() {
        List<String> excluded = properties.getExcludedPrefixes();
        List<String> silent   = properties.getSilentPrefixes();

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

            // Silent is checked first: a silent path is suppressed from BOTH the access log and
            // exclusions.log (recorded nowhere), so it must win over an excluded prefix it also
            // matches. Excluded (but not silent) paths are kept out of the access log yet still get
            // one audit line in exclusions.log below.
            boolean isSilent   = startsWithAny(path, silent);
            boolean isExcluded = isSilent || startsWithAny(path, excluded);

            if (!isExcluded) {
                chain.doFilter(req, res);
                return;
            }

            // Suppress from Tomcat's native access log
            req.setAttribute("skipLog", Boolean.TRUE);

            // A silent path stops here — no exclusions.log line. Pass through and return so the
            // finally-block writer never runs for it.
            if (isSilent) {
                chain.doFilter(req, res);
                return;
            }

            long start = System.currentTimeMillis();
            try {
                chain.doFilter(req, res);
            } finally {
                long   dur    = System.currentTimeMillis() - start;
                int    status = (res instanceof HttpServletResponse hres) ? hres.getStatus() : 0;
                String ip     = ClientIp.fromRequest(http);
                String ts     = LocalDateTime.now().format(TS_FMT);
                // X-Request-Id was set on the response by RequestIdFilter (which runs first).
                String reqId  = (res instanceof HttpServletResponse hres)
                        ? hres.getHeader(RequestIdFilter.HEADER) : null;
                // Serialize with Jackson rather than concatenating strings: `ip` derives from
                // the attacker-controlled X-Forwarded-For header (and `uri`/`method` are also
                // request-supplied), so hand-rolled escaping is easy to get wrong and invites
                // JSON log-injection / entry-forgery. Jackson escapes every value, and the
                // viewer parses this file back with the same ObjectMapper — symmetric by design.
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("ts", ts);
                rec.put("method", http.getMethod());
                rec.put("uri", uri);
                rec.put("status", status);
                rec.put("durationMs", dur);
                rec.put("ip", ip);
                if (reqId != null) rec.put("requestId", reqId);
                putIfPresent(rec, "user", http.getAttribute(USER_REQ_ATTR));
                putIfPresent(rec, "auth", http.getAttribute(AuthInfoFilter.AUTH_REQ_ATTR));
                putIfPresent(rec, "deny", http.getAttribute(AuthInfoFilter.DENY_REQ_ATTR));
                try {
                    EXCL_LOG.info("{}", OBJECT_MAPPER.writeValueAsString(rec));
                } catch (JsonProcessingException e) {
                    LOG.warn("Failed to serialize exclusion log entry for uri={}", uri, e);
                }
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

    private static boolean startsWithAny(String path, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }

    /** Adds {@code key} to {@code rec} only when the attribute is a non-blank String. */
    private static void putIfPresent(Map<String, Object> rec, String key, Object attr) {
        if (attr instanceof String s && !s.isBlank()) rec.put(key, s);
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
    private static volatile Method GET_CONTEXT;        // SecurityContextHolder.getContext()
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
