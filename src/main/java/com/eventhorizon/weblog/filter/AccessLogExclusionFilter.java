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
 * Default: {@code /admin/logs}, {@code /swagger-ui}, {@code /v3/api-docs}.
 * Override via {@code log-viewer.excluded-prefixes} in {@code application.properties}.
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
                // Escape backslash and double-quote so the URI stays valid JSON.
                String safeUri = uri.replace("\\", "\\\\").replace("\"", "\\\"");
                String json = "{\"ts\":\"" + ts + "\""
                        + ",\"method\":\"" + http.getMethod() + "\""
                        + ",\"uri\":\"" + safeUri + "\""
                        + ",\"status\":" + status
                        + ",\"durationMs\":" + dur
                        + ",\"ip\":\"" + ip + "\""
                        + (reqId != null ? ",\"requestId\":\"" + reqId + "\"" : "")
                        + "}";
                EXCL_LOG.info("{}", json);
            }
        });
        reg.addUrlPatterns("/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        reg.setName("accessLogExclusionFilter");
        return reg;
    }

    private static String resolveIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        String xri = req.getHeader("X-Real-IP");
        if (xri != null && !xri.isBlank()) return xri;
        return req.getRemoteAddr();
    }
}
