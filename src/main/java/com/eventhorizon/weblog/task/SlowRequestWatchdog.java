package com.eventhorizon.weblog.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.eventhorizon.weblog.WebLogProperties;
import com.eventhorizon.weblog.inflight.InFlightRegistry;
import com.eventhorizon.weblog.inflight.InFlightRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Periodically scans the {@link InFlightRegistry} and writes one log line for any request that has
 * been running longer than {@code log-viewer.slow-request-threshold}.
 *
 * <h2>Why this exists</h2>
 * The Tomcat access log records a request only at completion, so it cannot show a request that is
 * <i>still running</i> — nor one that never completes (hung worker, JVM death), nor one that
 * completes only after the client abandoned it (which Tomcat may log as a misleading
 * {@code connFate +} when the response fit the socket buffer before the abort was noticed). This
 * watchdog fills that gap: because it observes requests <b>while</b> they are in flight, a stuck or
 * abandoned request produces a {@code SLOW} line even if a normal completion line never arrives.
 *
 * <p>Reading model for the resulting {@code slow.log}:
 * <ul>
 *   <li>a {@code SLOW} line <b>with</b> a matching access-log line (same {@code requestId}) → the
 *       request was slow but finished; the access-log line carries its final status;</li>
 *   <li>a {@code SLOW} line <b>with no</b> matching access-log line → the request never finished
 *       (hung / killed) — exactly the case the access log alone hides.</li>
 * </ul>
 *
 * <h2>Purely observational</h2>
 * This watchdog never cancels, interrupts, or times out a request. The threshold is only the age at
 * which a still-running request becomes worth a log line — <b>not</b> a deadline. Each request is
 * reported at most once (a one-shot latch on {@link InFlightRequest}), so a long-running request is
 * logged when it crosses the threshold and never spammed thereafter.
 *
 * <p>Runs every {@code log-viewer.slow-request-sweep} (default 5s). The whole sweep is skipped when
 * {@code log-viewer.slow-request-logging-enabled=false}. {@code @EnableScheduling} is activated by
 * {@link com.eventhorizon.weblog.WebLogAutoConfiguration}, so the consumer app need not declare it.
 */
@Slf4j
public class SlowRequestWatchdog {

    /**
     * Logger name for the slow-request data stream — must match the {@code <logger name="...">}
     * in {@code logback-weblog-include.xml} so lines are routed to {@code logs/slow.log} and kept
     * out of the root console appender.
     */
    public static final String SLOW_LOGGER_NAME = "com.eventhorizon.weblog.slow";

    private static final Logger SLOW_LOG = LoggerFactory.getLogger(SLOW_LOGGER_NAME);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private final InFlightRegistry registry;
    private final boolean enabled;
    private final long thresholdNanos;

    public SlowRequestWatchdog(InFlightRegistry registry, WebLogProperties properties) {
        this.registry = registry;
        this.enabled = properties.isSlowRequestLoggingEnabled();
        this.thresholdNanos = properties.getSlowRequestThreshold().toNanos();
    }

    @Scheduled(fixedDelayString = "${log-viewer.slow-request-sweep:PT5S}",
               initialDelayString = "${log-viewer.slow-request-sweep:PT5S}")
    public void sweep() {
        if (!enabled) return;
        long now = System.nanoTime();
        for (InFlightRequest r : registry.snapshot()) {
            long ageNanos = now - r.getStartNanos();
            if (ageNanos >= thresholdNanos && r.markWarnedIfFirst()) {
                emit(r, ageNanos / 1_000_000L);
            }
        }
    }

    private void emit(InFlightRequest r, long inFlightMs) {
        // Jackson-serialized (not string-concatenated) because uri/ip derive from request-supplied
        // input (incl. the attacker-controlled X-Forwarded-For), so every value must be escaped to
        // prevent JSON log-injection. The viewer parses this file back with the same ObjectMapper.
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("ts", TS_FMT.format(Instant.ofEpochMilli(r.getStartEpochMs())));
        rec.put("event", "SLOW");
        rec.put("requestId", r.getRequestId());
        rec.put("method", r.getMethod());
        rec.put("uri", r.getUri());
        rec.put("protocol", r.getProtocol());
        rec.put("status", 0); // unknown — request still in flight when this line was written
        rec.put("inFlightMs", inFlightMs);
        rec.put("ip", r.getIp());
        rec.put("xForwardedFor", r.getXForwardedFor());
        rec.put("host", r.getHost());
        rec.put("ua", r.getUserAgent());
        rec.put("referer", r.getReferer());
        rec.put("thread", r.getThread());
        try {
            SLOW_LOG.info("{}", OBJECT_MAPPER.writeValueAsString(rec));
        } catch (JsonProcessingException e) {
            log.warn("SlowRequestWatchdog: failed to serialize slow-request entry for uri={}", r.getUri(), e);
        }
    }
}
