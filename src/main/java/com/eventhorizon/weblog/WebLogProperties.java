package com.eventhorizon.weblog;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

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
 *
 * # Opt in to request/response body capture (off by default — see {@link Body}):
 * log-viewer.body.enabled=true
 * </pre>
 */
@ConfigurationProperties("log-viewer")
@Getter
@Setter
public class WebLogProperties {

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
     * <p>Default covers the log-viewer itself, Swagger UI, the OpenAPI spec
     * endpoint, and Spring Boot Actuator so that these internal/tooling
     * requests do not pollute the API access log.
     */
    private List<String> excludedPrefixes = new ArrayList<>(
            List.of("/admin/logs", "/swagger-ui", "/v3/api-docs", "/actuator"));

    /** Request/response body capture settings. */
    @NestedConfigurationProperty
    private final Body body = new Body();

    /**
     * Opt-in request and response body capture. When
     * {@link Body#enabled enabled}, a filter wraps every request and response
     * with Spring's {@code ContentCachingRequestWrapper}/{@code ContentCachingResponseWrapper}
     * and writes a JSON line per request to {@code logs/bodies.log}. The log
     * viewer lazily fetches the body for a request via {@code /admin/logs/body?requestId=…}.
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
