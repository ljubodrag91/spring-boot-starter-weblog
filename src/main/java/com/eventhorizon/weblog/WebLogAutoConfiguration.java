package com.eventhorizon.weblog;

import com.eventhorizon.weblog.controller.LogViewerController;
import com.eventhorizon.weblog.filter.AccessLogExclusionFilter;
import com.eventhorizon.weblog.filter.AuthInfoFilter;
import com.eventhorizon.weblog.filter.BodyCaptureFilter;
import com.eventhorizon.weblog.filter.RequestIdFilter;
import com.eventhorizon.weblog.task.AccessLogCompressionTask;
import org.apache.catalina.valves.AccessLogValve;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
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
 *       the fixed 29-token pattern required by the log viewer parser — no
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
@Import({RequestIdFilter.class, AccessLogExclusionFilter.class, BodyCaptureFilter.class, AuthInfoFilter.class})
public class WebLogAutoConfiguration {

    // 32-token pattern — positions are read by index in LogViewerController.parseTomcatAccess().
    // Changing token order or inserting tokens in the middle silently breaks the parser.
    // Adding new tokens at the end is safe.
    // token[30] = safe Authorization summary set by AuthInfoFilter (never the raw credential)
    // token[31] = consumer-set auth-failure reason (AuthInfoFilter.DENY_REQ_ATTR)
    static final String ACCESS_LOG_PATTERN =
            "%{yyyy-MM-dd HH:mm:ss.SSS}t %m \"%U%q\" %H %s %b %D %F \"%a\"" +
            " \"%{X-Forwarded-For}i\" \"%{X-Real-IP}i\" %v:%p %I %X" +
            " \"%{Host}i\" \"%{Content-Type}i\" %{Content-Length}i" +
            " \"%{Accept}i\" \"%{Accept-Encoding}i\" \"%{Accept-Language}i\"" +
            " \"%{Connection}i\" \"%{Cache-Control}i\" \"%{X-Request-Id}i\"" +
            " \"%{Referer}i\" \"%{User-Agent}i\"" +
            " \"%{Content-Type}o\" %{Content-Length}o \"%{Content-Encoding}o\"" +
            " \"%{Cache-Control}o\" \"%{X-Request-Id}o\"" +
            " \"%{com.eventhorizon.weblog.user}r\"" +
            " \"%{com.eventhorizon.weblog.auth}r\"" +
            " \"%{com.eventhorizon.weblog.deny}r\"";

    // LogViewerController and AccessLogCompressionTask are instantiated here
    // (not via @Import) so Spring fully processes their @Value and @PostConstruct
    // annotations while keeping these classes free of Spring annotations.

    @Bean
    LogViewerController logViewerController() {
        return new LogViewerController();
    }

    @Bean
    AccessLogCompressionTask accessLogCompressionTask() {
        return new AccessLogCompressionTask();
    }

    @Bean
    @ConditionalOnClass(TomcatServletWebServerFactory.class)
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
            valve.setMaxDays(30);
            valve.setRotatable(true);
            valve.setRenameOnRotate(false);
            valve.setConditionUnless("skipLog");
            valve.setPattern(ACCESS_LOG_PATTERN);
            factory.addEngineValves(valve);
        };
    }
}
