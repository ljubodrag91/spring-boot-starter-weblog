package com.eventhorizon.weblog;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for the weblog starter.
 *
 * <p>All properties are under the {@code log-viewer} prefix.
 *
 * <pre>
 * # application.properties examples
 *
 * # Override which paths are excluded from the Tomcat access log:
 * log-viewer.excluded-prefixes=/admin/logs, /swagger-ui, /v3/api-docs, /actuator
 * </pre>
 */
@ConfigurationProperties("log-viewer")
@Getter
@Setter
public class WebLogProperties {

    /**
     * Master switch for the in-browser log viewer ({@code LogViewerController} and its
     * {@code /admin/logs/**} endpoints). When {@code false} the controller is not registered, so
     * none of the viewer/data/inflight/trace/page endpoints exist — the log-writing features
     * (request-id, exclusions, compression, slow-request watchdog) are unaffected.
     *
     * <p>Set to {@code false} on any deployment where you cannot put {@code /admin/logs/**} behind
     * authentication (see {@link com.eventhorizon.weblog.WebLogSecurityAutoConfiguration}); the viewer
     * otherwise exposes IPs, stack traces, and full request history with no auth when Spring
     * Security is not on the classpath. Default {@code true}.
     */
    private boolean enabled = true;

    /**
     * Optional authority required to access {@code /admin/logs/**} via the fallback security chain
     * ({@link com.eventhorizon.weblog.WebLogSecurityAutoConfiguration}). Blank (default) means any
     * authenticated principal is allowed; set e.g. {@code ROLE_ADMIN} to restrict further. Only
     * consulted when the fallback chain is active (Spring Security present and no overriding chain).
     */
    private String requiredAuthority = "";

    /**
     * Directory where Tomcat access log files are written.
     *
     * <p>Must match {@code server.tomcat.basedir} + this value. Relative paths are
     * resolved against {@code catalina.base}, which Spring Boot sets to
     * {@code server.tomcat.basedir} (default: a temp directory unless overridden).
     * Set {@code server.tomcat.basedir=.} in your app to keep logs in the working directory.
     *
     * <p>Both {@link com.eventhorizon.weblog.controller.LogViewerController} and
     * {@link com.eventhorizon.weblog.task.AccessLogCompressionTask} read this value so the
     * viewer and compressor always agree on where access log files live.
     */
    private String accessLogDirectory = "logs";

    /**
     * URI path prefixes (stripped of context path) that are suppressed from
     * the Tomcat native access log and written to {@code exclusions.log} instead.
     *
     * <p>Default covers the log-viewer itself, Swagger UI, and the OpenAPI spec
     * endpoint so that these internal/tooling requests do not pollute the API
     * access log.
     */
    private List<String> excludedPrefixes = new ArrayList<>(
            List.of("/admin/logs", "/swagger-ui", "/v3/api-docs", "/actuator"));

    /**
     * URI path prefixes (stripped of context path) that are suppressed from the Tomcat access log
     * <b>and</b> not written to {@code exclusions.log} — i.e. recorded nowhere at all. This is the
     * quieter tier below {@link #excludedPrefixes}: an excluded path is kept out of the access log
     * but still gets an audit line in {@code exclusions.log}, whereas a silent path leaves no trace
     * on either stream.
     *
     * <p>Intended for the viewer's own high-frequency self-traffic, which is pure noise in the
     * exclusions audit trail: without it the live in-flight strip's {@code GET /admin/logs/inflight}
     * poll (every {@code inflight-refresh}, default 3s, whenever the viewer is open) writes a fresh
     * {@code exclusions.log} line on every tick, drowning the genuine excluded traffic (Swagger,
     * the OpenAPI spec, the {@code /admin/logs} page load) it exists to record. The default silences
     * the viewer's data/asset/poll endpoints while leaving the bare {@code /admin/logs} page — a real
     * "an admin opened the viewer" event — in the exclusions log.
     *
     * <p>Matching is independent of {@link #excludedPrefixes}: a silent match alone sets {@code skipLog}
     * (so the path need not also be listed as excluded), and it is checked first so a path that matches
     * both (e.g. {@code /admin/logs/data} under the {@code /admin/logs} excluded prefix) is silenced
     * rather than logged. Operators can add their own noise here too — e.g. {@code /actuator/health}
     * liveness probes hit every few seconds.
     */
    private List<String> silentPrefixes = new ArrayList<>(
            List.of("/admin/logs/inflight", "/admin/logs/data", "/admin/logs/page",
                    "/admin/logs/trace", "/admin/logs/log-viewer."));

    /**
     * Name of a per-request attribute appended to the access log as the final
     * token ({@code %{name}r}). Apps set this attribute per request via
     * {@code request.setAttribute(name, value)}; the value is written to the
     * access log verbatim, with <b>no masking</b> — only set non-secret or
     * intentionally-exposed values.
     *
     * <p>The parsed value surfaces in the log-viewer JSON under this same key.
     * An empty value disables the trailing attribute token entirely.
     *
     * <p><b>Off by default</b> ({@code ""}). The value is written to the access log with no
     * masking and shown verbatim in the viewer, so this must be opted into deliberately — and
     * never pointed at a secret. (Historically defaulted to {@code "apiKey"}, which invited
     * logging secrets; set {@code log-viewer.request-attribute=apiKey} to restore that.)
     */
    private String requestAttribute = "";

    /**
     * Filename prefixes of the log families the compression task sweeps. Each is compressed
     * and pruned independently. Default covers the log streams the starter manages: the
     * Tomcat access log plus the Logback-written catalina/error/exclusions/slow logs (whose
     * appenders roll to plain {@code .log} precisely so this task can own their compression
     * and retention — see {@code logback-weblog-include.xml}).
     *
     * <p>Consumed via a property placeholder in
     * {@link com.eventhorizon.weblog.task.AccessLogCompressionTask} — which carries its own matching
     * {@code @Value} default, so keep the two in sync.
     */
    private List<String> compressionLogPrefixes = new ArrayList<>(
            List.of("access_log", "catalina", "error", "exclusions", "slow", "bodies"));

    /**
     * Whether the slow / never-completing request watchdog is active. When {@code true}
     * (default), {@link com.eventhorizon.weblog.filter.InFlightRequestFilter} tracks in-flight
     * requests and {@link com.eventhorizon.weblog.task.SlowRequestWatchdog} logs any that exceed
     * {@link #slowRequestThreshold} to {@code slow.log}.
     *
     * <p><b>Observational only</b> — the watchdog never cancels, interrupts, or times out a
     * request; it only writes log lines. Set to {@code false} to disable both the tracking
     * filter and the sweep entirely.
     */
    private boolean slowRequestLoggingEnabled = true;

    /**
     * How long a request may be in flight before the watchdog reports it as slow (one
     * {@code SLOW} line in {@code slow.log}). This is a <b>reporting threshold, not a
     * deadline</b>: nothing is terminated when it elapses — the request runs to its natural
     * end. Lower = earlier visibility and more log lines; higher = quieter but slower to
     * surface a stuck request. Default 30s.
     */
    private Duration slowRequestThreshold = Duration.ofSeconds(30);

    /**
     * How often the watchdog sweeps the in-flight registry. Default 5s. Also consumed directly
     * as the {@code fixedDelayString}/{@code initialDelayString} on
     * {@link com.eventhorizon.weblog.task.SlowRequestWatchdog}'s scheduled method via the
     * {@code log-viewer.slow-request-sweep} placeholder; this field mirrors that default so the
     * setting appears in configuration metadata.
     */
    private Duration slowRequestSweep = Duration.ofSeconds(5);

    /**
     * Whether the log viewer exposes the live in-flight request view — the read-only
     * {@code GET /admin/logs/inflight} endpoint and the live strip the frontend renders atop the
     * request view from it. Works independently of {@link #slowRequestLoggingEnabled}: the tracking
     * filter populates the shared registry when <em>either</em> this or slow-request logging is on,
     * so the live view functions even with {@code slow.log} writing disabled. Default {@code true}.
     */
    private boolean inflightViewEnabled = true;

    /**
     * How often the frontend polls {@code GET /admin/logs/inflight} to refresh the live in-flight
     * strip. Served into the viewer page. Default 3s — the view watches for tens-of-seconds hangs,
     * so a short poll is ample; lower it while actively investigating. Polling (not SSE) is
     * deliberate: it is stateless, holds no connection, and is not buffered by a reverse proxy.
     */
    private Duration inflightRefresh = Duration.ofSeconds(3);

    /**
     * Cron expression for the daily task that gzips the previous days' rolled log files
     * (which the writers rotate but leave uncompressed). Default is 00:10 — ten minutes
     * after midnight rotation — to let the OS release the just-closed file handle.
     *
     * <p>Consumed via a property placeholder on {@code @Scheduled} in
     * {@link com.eventhorizon.weblog.task.AccessLogCompressionTask}; the default here mirrors that
     * placeholder's default. Set to {@code -} to disable the schedule.
     */
    private String compressionCron = "0 10 0 * * *";

    /** Unit Tomcat writes the {@code %D}/{@code %F} access-log timing tokens in. */
    public enum AccessLogTimeUnit { MICROS, MILLIS }

    /**
     * Unit that Tomcat's {@code AccessLogValve} emits the {@code %D} (total duration) and
     * {@code %F} (time-to-first-byte) tokens in. The viewer normalises both to milliseconds for
     * display: {@code MICROS} divides by 1000, {@code MILLIS} leaves the value unchanged.
     *
     * <p>Default {@code MICROS} preserves the starter's historical behaviour. <b>Verify against
     * your Tomcat</b>: capture one {@code access_log} line and check the {@code %D} value for a
     * request you know took ~N ms — if it reads ~{@code N000} the unit is {@code MICROS}; if it
     * reads ~{@code N} it is {@code MILLIS}. Tomcat's own documentation describes {@code %D}/
     * {@code %F} as milliseconds, so if durations and p95 look 1000× too small, set this to
     * {@code MILLIS}.
     */
    private AccessLogTimeUnit accessLogTimeUnit = AccessLogTimeUnit.MICROS;

    /**
     * Maximum size of a single compressed log file the viewer will decompress and read.
     * Files larger than this are skipped (returned empty) to avoid excessive CPU/latency
     * on a viewer request. Raise it if legitimately large daily archives must stay visible.
     */
    private DataSize maxCompressedReadBytes = DataSize.ofMegabytes(50);

    /** Request/response body capture settings. */
    @NestedConfigurationProperty
    private final Body body = new Body();

    /**
     * Opt-in request and response body capture. When
     * {@link Body#enabled enabled}, a filter wraps every request and response
     * with Spring's {@code ContentCachingRequestWrapper}/{@code ContentCachingResponseWrapper}
     * and writes a JSON line per request to {@code logs/bodies.log}.
     *
     * <p><strong>Safety defaults:</strong>
     * <ul>
     *   <li>Disabled by default — bodies can contain secrets; opt-in only.</li>
     *   <li>{@link #maxBytes 8 KB} per side; oversize bodies are truncated and
     *       marked with a {@code "truncated":true} field on the captured JSON.</li>
     *   <li>{@link #skipContentTypes Multipart/binary types are skipped} entirely
     *       — capturing image/video uploads would blow the heap and offer no
     *       debugging value.</li>
     *   <li>{@link #redactKeys JSON keys matching the redact list} (password,
     *       secret, token, authorization by default) are replaced with
     *       {@code "***"} before write.</li>
     * </ul>
     */
    @Getter
    @Setter
    public static class Body {

        /** Master switch. When {@code false} no body capture filter is registered. */
        private boolean enabled = false;

        /**
         * Per-side cap (bytes). Bodies longer than this are truncated; the captured
         * JSON includes a {@code "truncated":true} marker so the viewer can show
         * the cap was hit. Default 8 KB keeps the bodies log small enough to fit
         * thousands of entries within a single rolled file.
         */
        private int maxBytes = 8192;

        /**
         * Case-insensitive JSON keys to redact before writing. A line such as
         * {@code "password":"hunter2"} becomes {@code "password":"***"} regardless
         * of where it appears in the body. Conservative regex-replace — does NOT
         * understand nested JSON, but handles the common flat-form cases.
         */
        private List<String> redactKeys = new ArrayList<>(
                List.of("password", "secret", "token", "authorization", "apiKey", "api_key"));

        /**
         * Content-type prefixes to skip entirely. The capture filter still wraps
         * the request/response but does not write any body payload to the log
         * when the request or response content-type starts with any of these.
         * Default skips multipart uploads and binary content (images/video/audio
         * /octet-stream).
         */
        private List<String> skipContentTypes = new ArrayList<>(
                List.of("multipart/", "image/", "video/", "audio/", "application/octet-stream"));
    }
}
