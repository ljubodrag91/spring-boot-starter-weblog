package com.eventhorizon.weblog.inflight;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Immutable snapshot of a single in-flight HTTP request, held in the
 * {@link InFlightRegistry} for the lifetime of the request (entry → completion).
 *
 * <p>Exists so the {@link com.eventhorizon.weblog.task.SlowRequestWatchdog} can observe
 * requests that are <b>still running</b> — something the Tomcat access log cannot do,
 * because it only writes a line when a request <i>completes</i>. A request that never
 * completes (hung worker, JVM death) or that completes only after the client already
 * gave up (logged as a misleading {@code connFate +}) is invisible in the access log;
 * this record is the handle the watchdog uses to surface it while it is still stuck.
 *
 * <p>Carries the <b>request-side</b> fields that already exist at request entry (method, uri,
 * protocol, host, client identity, user-agent, referer). It deliberately does <b>not</b> carry
 * response-side fields (status, bytes, timing, {@code connFate}, response headers): those do not
 * exist yet while the request is in flight, which is the whole point — this is a snapshot of an
 * unfinished request. For requests that do complete, the full picture is in the access-log line
 * with the same {@code requestId}.
 *
 * <p>{@link #warned} is a one-shot latch: the watchdog flips it the first time this
 * request is reported as slow, so a long-running request is logged once, not on every
 * sweep.
 */
public final class InFlightRequest {

    private final String requestId;
    private final String method;
    private final String uri;
    private final String protocol;
    private final String host;
    private final String ip;             // resolved client IP (XFF → X-Real-IP → remote)
    private final String xForwardedFor;  // raw proxy chain, for parity with the access log
    private final String userAgent;
    private final String referer;
    private final String thread;
    /** Monotonic clock reading at request entry — the basis for the elapsed-time check. */
    private final long startNanos;
    /** Wall-clock at entry (epoch millis) — for the human-readable "started at" in the log line. */
    private final long startEpochMs;
    /** Set once, when this request is first reported slow, so it is never re-logged. */
    private final AtomicBoolean warned = new AtomicBoolean(false);

    public InFlightRequest(String requestId, String method, String uri, String protocol,
                           String host, String ip, String xForwardedFor, String userAgent,
                           String referer, String thread, long startNanos, long startEpochMs) {
        this.requestId = requestId;
        this.method = method;
        this.uri = uri;
        this.protocol = protocol;
        this.host = host;
        this.ip = ip;
        this.xForwardedFor = xForwardedFor;
        this.userAgent = userAgent;
        this.referer = referer;
        this.thread = thread;
        this.startNanos = startNanos;
        this.startEpochMs = startEpochMs;
    }

    public String getRequestId()     { return requestId; }
    public String getMethod()        { return method; }
    public String getUri()           { return uri; }
    public String getProtocol()      { return protocol; }
    public String getHost()          { return host; }
    public String getIp()            { return ip; }
    public String getXForwardedFor() { return xForwardedFor; }
    public String getUserAgent()     { return userAgent; }
    public String getReferer()       { return referer; }
    public String getThread()        { return thread; }
    public long   getStartNanos()    { return startNanos; }
    public long   getStartEpochMs()  { return startEpochMs; }

    /** Atomically claims the one-shot "already reported slow" latch; true only for the first caller. */
    public boolean markWarnedIfFirst() {
        return warned.compareAndSet(false, true);
    }
}
