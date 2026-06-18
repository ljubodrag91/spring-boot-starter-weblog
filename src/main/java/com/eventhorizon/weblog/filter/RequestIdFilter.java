package com.eventhorizon.weblog.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Assigns a request ID to every HTTP request for cross-log correlation.
 *
 * <h2>What it does</h2>
 * <ol>
 *   <li>Reads {@code X-Request-Id} from the incoming request header. Accepts it if it
 *       looks safe (alphanumeric + {@code -_}, max 64 chars); generates a UUID otherwise.</li>
 *   <li>Sets {@code X-Request-Id} on the response — captured by Tomcat's native access log
 *       at token[28] ({@code %{X-Request-Id}o}) and surfaced as {@code requestId} in the
 *       log viewer.</li>
 *   <li>Puts the same value in {@link MDC} under the key {@code requestId} — appears in
 *       every Logback log line via the {@code [%X{requestId:-}]} console pattern.</li>
 *   <li>Removes the MDC key in {@code finally} to prevent leakage across thread-pool reuse.</li>
 * </ol>
 *
 * <h2>Ordering</h2>
 * Registered at {@link Ordered#HIGHEST_PRECEDENCE} so the request ID is available
 * everywhere in the filter chain. {@link AccessLogExclusionFilter} runs at
 * {@code HIGHEST_PRECEDENCE + 1} — when its {@code finally} block fires the response
 * header is already set, so it can include {@code requestId} in the exclusions log JSON.
 *
 * <h2>Downstream callers</h2>
 * Clients may supply their own {@code X-Request-Id} to propagate a trace ID end-to-end.
 * The same ID is then visible in all four log files (access, catalina, error, exclusions).
 */
@Configuration
public class RequestIdFilter {

    /** HTTP header name used for request ID propagation. */
    public static final String HEADER  = "X-Request-Id";

    /** SLF4J MDC key under which the request ID is stored during the request. */
    public static final String MDC_KEY = "requestId";

    /** Permits alphanumeric characters, hyphens, and underscores (UUID-safe, log-injection-safe). */
    private static final Pattern SAFE_ID = Pattern.compile("[a-zA-Z0-9\\-_]+");

    @Bean
    public FilterRegistrationBean<Filter> requestIdFilter() {
        FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>();
        reg.setFilter((ServletRequest req, ServletResponse res, FilterChain chain) -> {
            if (!(req instanceof HttpServletRequest http)) {
                chain.doFilter(req, res);
                return;
            }

            String requestId = resolveRequestId(http);

            if (res instanceof HttpServletResponse hres) {
                hres.setHeader(HEADER, requestId);
            }
            MDC.put(MDC_KEY, requestId);
            try {
                chain.doFilter(req, res);
            } finally {
                MDC.remove(MDC_KEY);
            }
        });
        reg.addUrlPatterns("/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        reg.setName("requestIdFilter");
        return reg;
    }

    /**
     * Returns the client-supplied {@code X-Request-Id} if it is safe to re-use,
     * or a freshly generated UUID otherwise.
     *
     * <p>A client-supplied value is accepted only when it is non-blank, at most 64
     * characters, and contains only alphanumeric characters, hyphens, or underscores.
     * Everything else — including values with control characters, quotes, or braces —
     * is replaced by a UUID to prevent log injection and JSON escaping issues.
     */
    private static String resolveRequestId(HttpServletRequest req) {
        String inbound = req.getHeader(HEADER);
        if (inbound != null && !inbound.isBlank()
                && inbound.length() <= 64
                && SAFE_ID.matcher(inbound).matches()) {
            return inbound;
        }
        return UUID.randomUUID().toString();
    }
}
