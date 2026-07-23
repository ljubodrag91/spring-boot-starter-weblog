package com.eventhorizon.weblog.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * Captures a <em>safe summary</em> of the request's {@code Authorization} header into
 * request attribute {@value #AUTH_REQ_ATTR}, which the Tomcat access log valve records
 * via {@code %{com.eventhorizon.weblog.auth}r}. The raw credential is never logged.
 *
 * <p>Summary shapes (all sanitized to quote-free printable ASCII, capped at
 * {@value #MAX_SUMMARY_LEN} chars):
 * <ul>
 *   <li>{@code Bearer JWT sub=user@example.com type=access exp=2026-07-05T10:20:58Z (expired 18m ago) len=812}
 *       — the JWT payload is base64-decoded <em>without signature verification</em> purely
 *       for display; {@code sub}, {@code type}/{@code typ} and {@code exp} are extracted
 *       when present.</li>
 *   <li>{@code Bearer opaque len=43} — bearer credential that is not a decodable JWT.</li>
 *   <li>{@code Basic user=admin} — only the username half of the decoded credential.</li>
 *   <li>{@code Digest len=120} — any other scheme: scheme name + credential length.</li>
 * </ul>
 *
 * <p>No attribute is set when the header is absent — the access log records {@code -},
 * which the viewer renders as "no Authorization header" on 401/403 entries.
 *
 * <h2>Deny-reason contract</h2>
 * Consumer applications may set request attribute {@value #DENY_REQ_ATTR} to a short
 * token (e.g. {@code jwt-expired}, {@code refresh-token-used-as-access}) at the point
 * where they reject a request. The valve records it via
 * {@code %{com.eventhorizon.weblog.deny}r} and the viewer shows it as
 * "Denied because" on the entry. Keep the value free of double quotes — it is written
 * into a quoted access-log token.
 */
@Configuration
public class AuthInfoFilter {

    /** Request attribute holding the safe Authorization summary (read by the access log valve). */
    public static final String AUTH_REQ_ATTR = "com.eventhorizon.weblog.auth";

    /** Request attribute consumer apps may set with a short auth-failure reason. */
    public static final String DENY_REQ_ATTR = "com.eventhorizon.weblog.deny";

    static final int MAX_SUMMARY_LEN = 160;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final DateTimeFormatter EXP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    @Bean
    public FilterRegistrationBean<Filter> authInfoFilter() {
        FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>();
        reg.setFilter((ServletRequest req, ServletResponse res, FilterChain chain) -> {
            if (req instanceof HttpServletRequest http) {
                String summary = summarize(http.getHeader("Authorization"), Instant.now());
                if (summary != null) http.setAttribute(AUTH_REQ_ATTR, summary);
            }
            chain.doFilter(req, res);
        });
        reg.addUrlPatterns("/*");
        // After RequestIdFilter/AccessLogExclusionFilter/InFlightRequestFilter/BodyCaptureFilter
        // (HP..HP+3).
        // Position is not load-bearing — the attribute only needs to exist before the
        // valve logs the request, which happens after the response completes.
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 4);
        reg.setName("authInfoFilter");
        return reg;
    }

    /**
     * Builds the safe summary for an {@code Authorization} header value, or {@code null}
     * when the header is absent/blank. {@code now} is injectable for tests.
     */
    static String summarize(String header, Instant now) {
        if (header == null || header.isBlank()) return null;
        String h = header.trim();
        int sp = h.indexOf(' ');
        String scheme = sp < 0 ? h : h.substring(0, sp);
        String cred   = sp < 0 ? "" : h.substring(sp + 1).trim();

        String out;
        if ("Bearer".equalsIgnoreCase(scheme))     out = summarizeBearer(cred, now);
        else if ("Basic".equalsIgnoreCase(scheme)) out = summarizeBasic(cred);
        else                                       out = scheme + " len=" + cred.length();
        return sanitize(out);
    }

    private static String summarizeBearer(String cred, Instant now) {
        String[] parts = cred.split("\\.", -1);
        if (parts.length == 3 && !parts[1].isEmpty()) {
            try {
                byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
                JsonNode claims = MAPPER.readTree(new String(payload, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder("Bearer JWT");
                JsonNode sub = claims.get("sub");
                if (sub != null && sub.isTextual()) sb.append(" sub=").append(sub.asText());
                JsonNode type = claims.has("type") ? claims.get("type") : claims.get("typ");
                if (type != null && type.isTextual()) sb.append(" type=").append(type.asText());
                JsonNode exp = claims.get("exp");
                if (exp != null && exp.canConvertToLong()) {
                    Instant expiry = Instant.ofEpochSecond(exp.asLong());
                    sb.append(" exp=").append(EXP_FMT.format(expiry));
                    if (expiry.isBefore(now)) {
                        sb.append(" (expired ").append(humanize(Duration.between(expiry, now))).append(" ago)");
                    } else {
                        sb.append(" (valid ").append(humanize(Duration.between(now, expiry))).append(" left)");
                    }
                }
                sb.append(" len=").append(cred.length());
                return sb.toString();
            } catch (Exception e) {
                // fall through — undecodable payload is treated as an opaque bearer token
            }
        }
        return "Bearer opaque len=" + cred.length();
    }

    private static String summarizeBasic(String cred) {
        try {
            String decoded = new String(Base64.getDecoder().decode(cred), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            if (colon > 0) return "Basic user=" + decoded.substring(0, colon);
        } catch (Exception e) {
            // fall through
        }
        return "Basic len=" + cred.length();
    }

    private static String humanize(Duration d) {
        long s = Math.max(0, d.getSeconds());
        if (s < 60)      return s + "s";
        if (s < 3600)    return (s / 60) + "m";
        if (s < 86400)   return (s / 3600) + "h" + ((s % 3600) / 60 > 0 ? (s % 3600) / 60 + "m" : "");
        return (s / 86400) + "d";
    }

    /**
     * Restricts the summary to printable ASCII minus {@code "} and {@code \} — the value
     * is written inside a double-quoted access-log token and into exclusion-log JSON, so
     * quote/backslash injection would shift the parser's token positions.
     */
    private static String sanitize(String s) {
        StringBuilder out = new StringBuilder(Math.min(s.length(), MAX_SUMMARY_LEN));
        for (int i = 0; i < s.length() && out.length() < MAX_SUMMARY_LEN; i++) {
            char c = s.charAt(i);
            out.append((c >= 0x20 && c <= 0x7e && c != '"' && c != '\\') ? c : '_');
        }
        return out.toString();
    }
}
