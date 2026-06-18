package com.eventhorizon.weblog;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
            List.of("/admin/logs", "/swagger-ui", "/v3/api-docs"));
}
