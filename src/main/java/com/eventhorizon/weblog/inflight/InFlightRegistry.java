package com.eventhorizon.weblog.inflight;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Registry of currently in-flight HTTP requests, keyed by a fresh per-registration token.
 *
 * <p>Populated by {@link com.eventhorizon.weblog.filter.InFlightRequestFilter} on request entry
 * and drained in its {@code finally} on completion; read by
 * {@link com.eventhorizon.weblog.task.SlowRequestWatchdog} on each sweep.
 *
 * <p>The key is a unique {@link AtomicLong} token, <b>not</b> the request ID. Request IDs can
 * repeat — a client may send the same {@code X-Request-Id} for concurrent requests (the
 * {@code RequestIdFilter} accepts client-supplied ids) — and keying on them would let one request
 * overwrite another's entry and its sibling's {@code finally} then remove the survivor, silently
 * dropping a still-running request from tracking. A per-registration token keeps every concurrent
 * request distinct regardless of its (display-only) request ID.
 *
 * <p><b>Purely observational.</b> Nothing here cancels, interrupts, or times out a request —
 * it only tracks which requests are running so the watchdog can <i>log</i> the long-running
 * ones. The map is bounded by the number of concurrent requests (i.e. the Tomcat worker pool),
 * so its footprint is negligible.
 *
 * <p>If a worker thread dies without running its {@code finally} (e.g. a JVM-fatal error), its
 * entry lingers — acceptable, because the process is going down anyway, and a lingering entry
 * only ever produces one extra "slow" log line, never a leak that grows unbounded.
 */
public class InFlightRegistry {

    private final ConcurrentHashMap<Long, InFlightRequest> inFlight = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    /**
     * Registers a request under a fresh unique token and returns it. The caller must pass that
     * token to {@link #remove(long)} on completion (never the request ID).
     */
    public long register(InFlightRequest request) {
        long key = seq.incrementAndGet();
        inFlight.put(key, request);
        return key;
    }

    public void remove(long key) {
        inFlight.remove(key);
    }

    /** Live view of the current entries — safe to iterate (weakly-consistent) for read-only sweeps. */
    public Collection<InFlightRequest> snapshot() {
        return inFlight.values();
    }

    public int size() {
        return inFlight.size();
    }
}
