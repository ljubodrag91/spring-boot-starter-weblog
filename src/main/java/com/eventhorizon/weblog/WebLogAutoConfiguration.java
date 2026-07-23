package com.eventhorizon.weblog;

import com.eventhorizon.weblog.controller.LogViewerController;
import com.eventhorizon.weblog.filter.AccessLogExclusionFilter;
import com.eventhorizon.weblog.filter.AuthInfoFilter;
import com.eventhorizon.weblog.filter.BodyCaptureFilter;
import com.eventhorizon.weblog.filter.InFlightRequestFilter;
import com.eventhorizon.weblog.filter.RequestIdFilter;
import com.eventhorizon.weblog.inflight.InFlightRegistry;
import com.eventhorizon.weblog.task.AccessLogCompressionTask;
import com.eventhorizon.weblog.task.SlowRequestWatchdog;
import org.apache.catalina.valves.AccessLogValve;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Auto-configuration entry point for the weblog starter.
 *
 * <p>Registers all components when the application is a servlet-based Spring MVC app:
 *
 * <ul>
 *   <li>{@link RequestIdFilter} — assigns {@code X-Request-Id} to every request</li>
 *   <li>{@link AccessLogExclusionFilter} — suppresses configured paths from the Tomcat
 *       access log and writes them to {@code exclusions.log}</li>
 *   <li>{@link LogViewerController} — serves the {@code /admin/logs} viewer page</li>
 *   <li>{@link AccessLogCompressionTask} — gzip-compresses rolled Tomcat access logs
 *       every night at 00:10</li>
 *   <li>A {@link WebServerFactoryCustomizer} that wires the {@code AccessLogValve} with
 *       the fixed 34-token pattern required by the log viewer parser — no
 *       {@code server.tomcat.accesslog.*} properties needed in the consumer app.</li>
 * </ul>
 *
 * <p>{@code @EnableScheduling} is declared here so the compression task fires without
 * requiring the consumer app to add it to their {@code @SpringBootApplication}.
 * A duplicate declaration in the consumer is harmless (idempotent).
 */
@AutoConfiguration
@EnableScheduling
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(WebLogProperties.class)
@Import({RequestIdFilter.class, AccessLogExclusionFilter.class, InFlightRequestFilter.class,
         AuthInfoFilter.class, BodyCaptureFilter.class})
public class WebLogAutoConfiguration {

    // 34-token base pattern — positions are read by index in LogParser.parseTomcatAccess().
    // Changing token order or inserting tokens in the middle silently breaks the parser.
    // Adding new tokens at the end is safe. Never add a request-HEADER token (%{name}i) for
    // anything secret (e.g. an API key) — header/response-header elements capture values
    // verbatim, with no masking, straight to a persistent log file. A request-ATTRIBUTE
    // element (%{name}r) is safer only in that the app controls what's set there — the value
    // is still logged verbatim (unmasked), so the app decides whether a secret goes in.
    //
    // Tokens 31-33 are the request attributes this starter contributes:
    //   [31] user — authenticated principal, set by AccessLogExclusionFilter.principalCaptureFilter()
    //   [32] auth — safe Authorization-header summary, set by AuthInfoFilter (never the raw credential)
    //   [33] deny — short auth-failure reason, set by the CONSUMER app via AuthInfoFilter.DENY_REQ_ATTR
    // The 35th token (%{<requestAttribute>}r) is appended at valve-configuration time from
    // WebLogProperties.requestAttribute — see accessLogPattern(WebLogProperties).
    static final String ACCESS_LOG_PATTERN =
            "%{yyyy-MM-dd HH:mm:ss.SSS}t %m \"%U%q\" %H %s %b %D %F \"%a\"" +
            " \"%{X-Forwarded-For}i\" \"%{X-Real-IP}i\" %v:%p %I %X" +
            " \"%{Host}i\" \"%{Content-Type}i\" %{Content-Length}i" +
            " \"%{Accept}i\" \"%{Accept-Encoding}i\" \"%{Accept-Language}i\"" +
            " \"%{Connection}i\" \"%{Cache-Control}i\" \"%{X-Request-Id}i\"" +
            " \"%{Referer}i\" \"%{User-Agent}i\"" +
            " \"%{Content-Type}o\" %{Content-Length}o \"%{Content-Encoding}o\"" +
            " \"%{Cache-Control}o\" \"%{X-Request-Id}o\"" +
            " \"%{X-RateLimit-Limit}o\" \"%{X-RateLimit-Remaining}o\"" +
            " \"%{" + AccessLogExclusionFilter.USER_REQ_ATTR + "}r\"" +
            " \"%{" + AuthInfoFilter.AUTH_REQ_ATTR + "}r\"" +
            " \"%{" + AuthInfoFilter.DENY_REQ_ATTR + "}r\"";

    /**
     * Builds the access-log pattern: the fixed {@link #ACCESS_LOG_PATTERN} base plus a
     * trailing {@code %{<name>}r} request-attribute token when
     * {@link WebLogProperties#getRequestAttribute()} is non-empty. Kept at position 34
     * (0-indexed) so the positional parser in {@link com.eventhorizon.weblog.controller.LogParser}
     * is unaffected.
     */
    static String accessLogPattern(WebLogProperties props) {
        String attr = props.getRequestAttribute();
        if (attr == null || attr.isBlank()) {
            return ACCESS_LOG_PATTERN;
        }
        String trimmed = attr.trim();
        // Guard the interpolation: a value containing '}' or '"' would corrupt the valve pattern
        // and silently mis-index every field the positional parser reads. Restrict to a
        // conservative identifier charset (same class LogViewerController.safeReqAttr enforces);
        // an invalid value disables the token rather than producing a broken pattern.
        if (!trimmed.matches("[A-Za-z0-9_.-]+")) {
            return ACCESS_LOG_PATTERN;
        }
        return ACCESS_LOG_PATTERN + " \"%{" + trimmed + "}r\"";
    }

    // LogViewerController and AccessLogCompressionTask are instantiated here
    // (not via @Import) so Spring fully processes their @Value and @PostConstruct
    // annotations while keeping these classes free of Spring annotations.

    // Gated by log-viewer.enabled (default true): when false the viewer's HTTP endpoints do not
    // exist at all, so an operator who cannot authenticate /admin/logs/** can switch off the
    // exposure without dropping the log-writing features.
    @Bean
    @ConditionalOnProperty(prefix = "log-viewer", name = "enabled", havingValue = "true", matchIfMissing = true)
    LogViewerController logViewerController() {
        return new LogViewerController();
    }

    @Bean
    AccessLogCompressionTask accessLogCompressionTask() {
        return new AccessLogCompressionTask();
    }

    /** Shared store of in-flight requests, written by the filter and read by the watchdog. */
    @Bean
    InFlightRegistry inFlightRegistry() {
        return new InFlightRegistry();
    }

    /**
     * Observational watchdog that logs requests still running past
     * {@code log-viewer.slow-request-threshold} to {@code slow.log}. Never cancels or times out
     * a request — see {@link SlowRequestWatchdog}.
     */
    @Bean
    SlowRequestWatchdog slowRequestWatchdog(InFlightRegistry registry, WebLogProperties properties) {
        return new SlowRequestWatchdog(registry, properties);
    }

    // Tomcat-specific wiring lives in this nested @ConditionalOnClass configuration so the outer
    // auto-configuration never names Tomcat types in its own bean signatures. A consumer on
    // Jetty/Undertow (having excluded Tomcat from spring-boot-starter-web) can therefore load
    // this auto-configuration without the Tomcat classes present — the condition is evaluated
    // from class metadata before any Tomcat type is resolved.
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(TomcatServletWebServerFactory.class)
    static class TomcatAccessLogConfiguration {

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> weblogAccessLogCustomizer(
            WebLogProperties props) {
        return factory -> {
            AccessLogValve valve = new AccessLogValve();
            // Resolve to absolute so the access log lands in the same directory as the
            // Logback-managed files (catalina.log, exclusions.log), which also resolve
            // relative paths against user.dir. Without this, a relative path would
            // resolve against catalina.base (server.tomcat.basedir), which defaults to
            // a temp directory — scattering access logs away from the other log files.
            String dir = props.getAccessLogDirectory();
            if (!java.nio.file.Paths.get(dir).isAbsolute()) {
                dir = java.nio.file.Paths.get(System.getProperty("user.dir"), dir).toString();
            }
            valve.setDirectory(dir);
            valve.setPrefix("access_log");
            valve.setSuffix(".log");
            valve.setFileDateFormat(".yyyy-MM-dd");
            // -1 = never delete. Retention is intentionally disabled: the compression task keeps
            // archives indefinitely, and Tomcat's own maxDays cleanup would only ever match plain
            // access_log.<date>.log names (not the .gz the task produces) anyway. Bounding disk is
            // left to the operator.
            valve.setMaxDays(-1);
            valve.setRotatable(true);
            valve.setRenameOnRotate(false);
            valve.setConditionUnless("skipLog");
            // Flush each entry immediately. Tomcat's default (buffered=true) holds lines in an
            // unflushed writer, so on a low-traffic service a just-completed request — including
            // a slow/timed-out one — can stay invisible in the file for a long time. The access
            // log is meant to be watched live (and read by the viewer), so prompt visibility wins
            // over the small per-request flush cost.
            valve.setBuffered(false);
            valve.setPattern(accessLogPattern(props));
            factory.addEngineValves(valve);
        };
    }
    }
}
