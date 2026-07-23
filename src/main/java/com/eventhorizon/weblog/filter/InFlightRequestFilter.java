package com.eventhorizon.weblog.filter;

import com.eventhorizon.weblog.WebLogProperties;
import com.eventhorizon.weblog.inflight.InFlightRegistry;
import com.eventhorizon.weblog.inflight.InFlightRequest;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Records every request in the {@link InFlightRegistry} on entry and removes it on completion,
 * so the {@link com.eventhorizon.weblog.task.SlowRequestWatchdog} can report requests that are still
 * running past a threshold.
 *
 * <h2>Why this exists</h2>
 * The Tomcat access log writes a line only when a request <b>completes</b>. A request that never
 * completes — or that completes only after the client abandoned it (logged as a misleading
 * {@code connFate +}, because the response fit the socket buffer before the abort was noticed) —
 * leaves no usable trace. This filter + the watchdog make in-flight and never-completing requests
 * visible independent of Tomcat's completion-time bookkeeping.
 *
 * <h2>Purely observational</h2>
 * This filter never cancels, interrupts, or times out a request. It only adds/removes a map entry
 * around {@code chain.doFilter}; the request proceeds exactly as it would without it.
 *
 * <h2>Ordering</h2>
 * Registered at {@link Ordered#HIGHEST_PRECEDENCE}{@code  + 2} — after {@link RequestIdFilter}
 * ({@code HIGHEST_PRECEDENCE}), so the {@code X-Request-Id} response header is already set and can
 * be recorded on the in-flight entry for display/correlation (the registry itself keys on a
 * per-registration token, not this id — see {@link InFlightRegistry}), and after {@link AccessLogExclusionFilter}
 * ({@code HIGHEST_PRECEDENCE + 1}), so its {@code skipLog} request attribute is already set and
 * excluded paths (log viewer, Swagger) can be skipped — keeping the watchdog focused on real
 * traffic rather than tooling requests.
 */
@Configuration
@RequiredArgsConstructor
public class InFlightRequestFilter {

    private final InFlightRegistry registry;
    private final WebLogProperties properties;

    @Bean
    public FilterRegistrationBean<Filter> inFlightRequestFilter() {
        FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>();
        reg.setFilter((ServletRequest req, ServletResponse res, FilterChain chain) -> {
            // Track only if a consumer needs the registry — the watchdog (slow-request logging) OR the
            // live in-flight view. If neither is enabled, or non-HTTP, or excluded (skipLog set by
            // AccessLogExclusionFilter) → transparent pass-through, zero overhead.
            boolean track = properties.isSlowRequestLoggingEnabled() || properties.isInflightViewEnabled();
            if (!track
                    || !(req instanceof HttpServletRequest http)
                    || Boolean.TRUE.equals(req.getAttribute("skipLog"))) {
                chain.doFilter(req, res);
                return;
            }

            // Request ID (display/correlation only; the registry keys on its own token) — set on the
            // response by RequestIdFilter (HIGHEST_PRECEDENCE), which has already run.
            String requestId = (res instanceof HttpServletResponse hres)
                    ? hres.getHeader(RequestIdFilter.HEADER) : null;

            long token = registry.register(new InFlightRequest(
                    requestId,
                    http.getMethod(),
                    buildUri(http),
                    http.getProtocol(),
                    http.getHeader("Host"),
                    ClientIp.fromRequest(http),
                    http.getHeader("X-Forwarded-For"),
                    http.getHeader("User-Agent"),
                    http.getHeader("Referer"),
                    Thread.currentThread().getName(),
                    System.nanoTime(),
                    System.currentTimeMillis()));
            try {
                chain.doFilter(req, res);
            } finally {
                registry.remove(token);
            }
        });
        reg.addUrlPatterns("/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        reg.setName("inFlightRequestFilter");
        return reg;
    }

    /**
     * The request path only — the query string is deliberately dropped. It is stored on the
     * in-flight record and persisted verbatim to {@code slow.log}, and query strings routinely
     * carry secrets (tokens, keys); keeping just the path avoids writing them to disk.
     */
    private static String buildUri(HttpServletRequest req) {
        return req.getRequestURI();
    }
}
