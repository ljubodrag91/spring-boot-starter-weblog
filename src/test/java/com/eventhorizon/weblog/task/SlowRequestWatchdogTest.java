package com.eventhorizon.weblog.task;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.eventhorizon.weblog.WebLogProperties;
import com.eventhorizon.weblog.inflight.InFlightRegistry;
import com.eventhorizon.weblog.inflight.InFlightRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SlowRequestWatchdog}: reports a request past the threshold exactly once
 * (the one-shot latch), leaves fresh requests alone, and does nothing when disabled.
 */
class SlowRequestWatchdogTest {

    private InFlightRegistry registry;
    private ListAppender<ILoggingEvent> appender;
    private Logger slowLogger;

    @BeforeEach
    void setUp() {
        registry = new InFlightRegistry();
        slowLogger = (Logger) LoggerFactory.getLogger(SlowRequestWatchdog.SLOW_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        slowLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        slowLogger.detachAppender(appender);
    }

    private WebLogProperties props(boolean enabled, Duration threshold) {
        WebLogProperties p = new WebLogProperties();
        p.setSlowRequestLoggingEnabled(enabled);
        p.setSlowRequestThreshold(threshold);
        return p;
    }

    /** A request that started well before the threshold (startNanos in the past). */
    private void registerOldRequest(long ageMillis) {
        long startNanos = System.nanoTime() - ageMillis * 1_000_000L;
        registry.register(new InFlightRequest("req-1", "GET", "/slow", "HTTP/1.1", "host",
                "1.2.3.4", "1.2.3.4", "ua", null, "exec-1", startNanos,
                System.currentTimeMillis() - ageMillis));
    }

    @Test
    void requestPastThreshold_reportedOnceThenLatched() {
        SlowRequestWatchdog w = new SlowRequestWatchdog(registry, props(true, Duration.ofSeconds(30)));
        registerOldRequest(31_000); // 31s in flight ≥ 30s threshold

        w.sweep();
        assertThat(appender.list).as("one SLOW line on the first crossing").hasSize(1);

        w.sweep();
        assertThat(appender.list).as("latched — never re-logged on later sweeps").hasSize(1);
    }

    @Test
    void slowLine_isValidJsonWithExpectedFields() throws Exception {
        SlowRequestWatchdog w = new SlowRequestWatchdog(registry, props(true, Duration.ofSeconds(30)));
        registerOldRequest(31_000);

        w.sweep();

        JsonNode n = new ObjectMapper().readTree(appender.list.get(0).getFormattedMessage());
        assertThat(n.get("event").asText()).isEqualTo("SLOW");
        assertThat(n.get("requestId").asText()).isEqualTo("req-1");
        assertThat(n.get("uri").asText()).isEqualTo("/slow");
        assertThat(n.get("status").asInt()).isZero(); // still in flight
        assertThat(n.get("inFlightMs").asLong()).isGreaterThanOrEqualTo(30_000L);
    }

    @Test
    void freshRequestBelowThreshold_notReported() {
        SlowRequestWatchdog w = new SlowRequestWatchdog(registry, props(true, Duration.ofSeconds(30)));
        registerOldRequest(1_000); // 1s ≪ 30s

        w.sweep();
        assertThat(appender.list).isEmpty();
    }

    @Test
    void disabled_sweepDoesNothing() {
        SlowRequestWatchdog w = new SlowRequestWatchdog(registry, props(false, Duration.ofSeconds(30)));
        registerOldRequest(60_000);

        w.sweep();
        assertThat(appender.list).isEmpty();
    }
}
