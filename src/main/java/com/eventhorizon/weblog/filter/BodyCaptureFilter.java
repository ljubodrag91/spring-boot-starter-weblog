package com.eventhorizon.weblog.filter;

import com.eventhorizon.weblog.WebLogProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Captures request and response bodies and writes them to {@code logs/bodies.log}
 * as JSON lines correlated by {@code X-Request-Id}.
 *
 * <p>Auto-configured only when {@code log-viewer.body.enabled=true}. The filter is
 * registered at {@code HIGHEST_PRECEDENCE + 3} — after {@link RequestIdFilter}
 * (so the response has the request ID), after {@link AccessLogExclusionFilter}
 * (so exclusion suppression and body capture are independent concerns), and after
 * {@link InFlightRequestFilter} ({@code HIGHEST_PRECEDENCE + 2}), which must wrap the
 * request as early as possible so a hung request is still recorded in the registry.
 *
 * <h2>Wire format</h2>
 * Each captured line is a single JSON object on one line, e.g.:
 * <pre>{@code
 * {"ts":"2026-06-18 14:32:01.502","requestId":"abc","method":"POST",
 *  "uri":"/api/v1/login","status":200,"reqCT":"application/json",
 *  "reqBody":"{\"email\":\"u@example.com\",\"password\":\"***\"}",
 *  "reqBytes":47,"reqTruncated":false,
 *  "resCT":"application/json","resBody":"{\"token\":\"...\"}",
 *  "resBytes":1200,"resTruncated":false}
 * }</pre>
 *
 * <h2>Safety</h2>
 * <ul>
 *   <li>Per-side cap of {@link WebLogProperties.Body#getMaxBytes()} bytes
 *       (default 8 KB) — exceeding bodies are truncated and the line carries
 *       {@code reqTruncated/resTruncated=true}.</li>
 *   <li>Multipart and binary content-types (image/video/audio/octet-stream by
 *       default) are skipped entirely.</li>
 *   <li>Configured JSON keys (password/secret/token/authorization by default) are
 *       redacted to {@code "***"} via a flat regex substitution. The substitution
 *       is intentionally simple — it covers the common flat-form leak vectors
 *       (top-level login bodies, simple OAuth token responses) without
 *       attempting full JSON traversal. Nested complex JSON may not redact;
 *       review your bodies log before treating it as safe to share externally.</li>
 *   <li>Response body is written to the captured wrapper but NOT to the client
 *       until {@link ContentCachingResponseWrapper#copyBodyToResponse()} is
 *       called in the {@code finally} block — required, or the client never
 *       receives the response body.</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(prefix = "log-viewer.body", name = "enabled", havingValue = "true")
public class BodyCaptureFilter {

    /**
     * Logger name used by this filter — must match the {@code <logger name="...">}
     * in {@code logback-weblog-include.xml} so that body lines are routed to
     * {@code logs/bodies.log}.
     */
    public static final String BODIES_LOGGER_NAME = "com.eventhorizon.weblog.bodies";

    private static final Logger BODY_LOG = LoggerFactory.getLogger(BODIES_LOGGER_NAME);

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> bodyCaptureFilter(WebLogProperties props) {
        WebLogProperties.Body cfg = props.getBody();
        // Pre-compile redaction patterns once. Each pattern matches a JSON-style
        // "key":"value" pair where the key (case-insensitive) is in the redact list.
        // The value run is captured non-greedily up to the next unescaped quote.
        Pattern redact = compileRedactPattern(cfg.getRedactKeys());

        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req,
                                            HttpServletResponse res,
                                            FilterChain chain) throws ServletException, IOException {

                // Skip non-capturable content-types early — no wrapping cost.
                String reqCtRaw = req.getContentType();
                boolean skipReqByCt = matchesAnyPrefix(reqCtRaw, cfg.getSkipContentTypes());

                ContentCachingRequestWrapper  reqW = new ContentCachingRequestWrapper(req);
                ContentCachingResponseWrapper resW = new ContentCachingResponseWrapper(res);

                long start = System.currentTimeMillis();
                try {
                    chain.doFilter(reqW, resW);
                } finally {
                    long dur = System.currentTimeMillis() - start;

                    String resCtRaw = resW.getContentType();
                    boolean skipResByCt = matchesAnyPrefix(resCtRaw, cfg.getSkipContentTypes());

                    byte[] reqBytes = skipReqByCt ? new byte[0] : reqW.getContentAsByteArray();
                    byte[] resBytes = skipResByCt ? new byte[0] : resW.getContentAsByteArray();

                    String reqStr = decode(reqBytes, cfg.getMaxBytes(), reqCtRaw);
                    String resStr = decode(resBytes, cfg.getMaxBytes(), resCtRaw);
                    boolean reqTrunc = reqBytes.length > cfg.getMaxBytes();
                    boolean resTrunc = resBytes.length > cfg.getMaxBytes();

                    if (redact != null) {
                        if (reqStr != null) reqStr = redact.matcher(reqStr).replaceAll("$1\"***\"");
                        if (resStr != null) resStr = redact.matcher(resStr).replaceAll("$1\"***\"");
                    }

                    String reqId = res.getHeader(RequestIdFilter.HEADER);
                    String ts    = LocalDateTime.now().format(TS_FMT);

                    StringBuilder json = new StringBuilder(256)
                            .append("{\"ts\":\"").append(ts).append("\"")
                            .append(",\"requestId\":\"").append(esc(reqId)).append("\"")
                            .append(",\"method\":\"").append(req.getMethod()).append("\"")
                            .append(",\"uri\":\"").append(esc(req.getRequestURI())).append("\"")
                            .append(",\"status\":").append(resW.getStatus())
                            .append(",\"durationMs\":").append(dur);
                    if (reqCtRaw != null) json.append(",\"reqCT\":\"").append(esc(reqCtRaw)).append("\"");
                    json.append(",\"reqBytes\":").append(reqBytes.length);
                    if (skipReqByCt) json.append(",\"reqSkipped\":true");
                    else if (reqStr != null) json.append(",\"reqBody\":\"").append(esc(reqStr)).append("\"");
                    if (reqTrunc) json.append(",\"reqTruncated\":true");

                    if (resCtRaw != null) json.append(",\"resCT\":\"").append(esc(resCtRaw)).append("\"");
                    json.append(",\"resBytes\":").append(resBytes.length);
                    if (skipResByCt) json.append(",\"resSkipped\":true");
                    else if (resStr != null) json.append(",\"resBody\":\"").append(esc(resStr)).append("\"");
                    if (resTrunc) json.append(",\"resTruncated\":true");
                    json.append("}");

                    BODY_LOG.info("{}", json);

                    // MUST run — without it the client never receives the response body
                    // because we replaced the output stream with a buffering wrapper.
                    resW.copyBodyToResponse();
                }
            }
        };

        FilterRegistrationBean<OncePerRequestFilter> reg = new FilterRegistrationBean<>(filter);
        reg.addUrlPatterns("/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 3);
        reg.setName("bodyCaptureFilter");
        return reg;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────

    private static boolean matchesAnyPrefix(String contentType, List<String> prefixes) {
        if (contentType == null || prefixes == null) return false;
        String ct = contentType.toLowerCase();
        for (String p : prefixes) {
            if (ct.startsWith(p.toLowerCase())) return true;
        }
        return false;
    }

    private static String decode(byte[] bytes, int maxBytes, String contentType) {
        if (bytes == null || bytes.length == 0) return null;
        Charset cs = charsetFromCt(contentType);
        int len = Math.min(bytes.length, maxBytes);
        return new String(bytes, 0, len, cs);
    }

    private static Charset charsetFromCt(String ct) {
        if (ct == null) return StandardCharsets.UTF_8;
        int idx = ct.toLowerCase().indexOf("charset=");
        if (idx < 0) return StandardCharsets.UTF_8;
        String name = ct.substring(idx + 8).trim();
        // Strip any trailing parameter / quote
        int sc = name.indexOf(';');
        if (sc >= 0) name = name.substring(0, sc).trim();
        name = name.replace("\"", "").replace("'", "");
        try { return Charset.forName(name); } catch (Exception e) { return StandardCharsets.UTF_8; }
    }

    /** Escape characters that would break a single-line JSON value. */
    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0, n = s.length(); i < n; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '"':  out.append("\\\""); break;
                case '\n': out.append("\\n");  break;
                case '\r': out.append("\\r");  break;
                case '\t': out.append("\\t");  break;
                case '\b': out.append("\\b");  break;
                case '\f': out.append("\\f");  break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Builds a regex that matches {@code "key"\s*:\s*"value"} pairs whose key
     * (case-insensitive) is in {@code keys}. The replacement uses group 1 to
     * preserve the original key + colon, and overwrites the value to {@code "***"}.
     *
     * <p>Returns {@code null} when {@code keys} is empty — callers skip
     * substitution entirely in that case.
     */
    private static Pattern compileRedactPattern(List<String> keys) {
        if (keys == null || keys.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("((?i)\"(?:");
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) sb.append('|');
            sb.append(Pattern.quote(keys.get(i)));
        }
        sb.append(")\"\\s*:\\s*)\"(?:\\\\.|[^\"\\\\])*\"");
        return Pattern.compile(sb.toString());
    }
}
