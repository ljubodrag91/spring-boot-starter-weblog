package com.eventhorizon.weblog.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.eventhorizon.weblog.controller.LogViewerController.LogEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The starter's log text→{@link LogEntry} parsing layer, for all four on-disk formats: the Tomcat
 * access log (positional, quoted tokens), the server logs (catalina/error, with stack-trace
 * folding), and the two compact-JSON streams (exclusions, slow). Also owns the forward-streaming
 * entry walks used by paging — it composes {@link LogFileReader} for the raw line I/O and turns
 * those lines into entries.
 *
 * <p>Stateless except for the small amount of <em>live</em> configuration {@link #parseTomcatAccess}
 * needs — the request-attribute name, the {@code forward-headers-strategy}, and the {@code %D}/
 * {@code %F} time-unit divisor — supplied as accessors so the controller's config stays the single
 * source of truth.
 */
final class LogParser {

    private static final Logger log = LoggerFactory.getLogger(LogParser.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Streams a file's entries chronologically; return {@code false} from {@link #entry} to stop. */
    @FunctionalInterface
    interface EntryHandler { boolean entry(int index, LogEntry e); }

    private final LogFileReader reader;
    private final Supplier<String> requestAttributeName;
    private final Supplier<String> forwardHeadersStrategy;
    private final LongSupplier timeDivisor;

    LogParser(LogFileReader reader,
              Supplier<String> requestAttributeName,
              Supplier<String> forwardHeadersStrategy,
              LongSupplier timeDivisor) {
        this.reader = reader;
        this.requestAttributeName = requestAttributeName;
        this.forwardHeadersStrategy = forwardHeadersStrategy;
        this.timeDivisor = timeDivisor;
    }

    static boolean isServerType(String type) {
        return "catalina".equals(type) || "error".equals(type);
    }

    /** Parses one entry per line via {@code parse}, dropping nulls; input order preserved. */
    static List<LogEntry> mapLines(List<String> lines, Function<String, LogEntry> parse) {
        List<LogEntry> out = new ArrayList<>();
        for (String line : lines) {
            LogEntry e = parse.apply(line);
            if (e != null) out.add(e);
        }
        return out;
    }

    // ── Tomcat native access log ──────────────────────────────────────────────
    // Pattern positions (after timestamp):
    //   method=0 uri=1 protocol=2 status=3 respBytes=4 durationMs=5 ttfbMs=6 remoteIp=7
    //   xForwardedFor=8 referer=22 ua=23 requestId=28 rateLimitLimit=29 rateLimitRemaining=30
    //   apiKey=31 (value of the configured requestAttribute)

    private static final Pattern TOMCAT_ACCESS_TS =
            Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}\\s\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+");

    /** Returns token at index i, or null if absent or equal to "-". */
    private static String tget(List<String> list, int i) {
        if (i >= list.size()) return null;
        String v = list.get(i);
        return (v.isEmpty() || "-".equals(v)) ? null : v;
    }

    LogEntry parseTomcatAccess(String line) {
        if (line == null || line.isBlank()) return null;
        Matcher m = TOMCAT_ACCESS_TS.matcher(line);
        if (!m.find()) return null;
        String ts   = reformatTs(m.group(1));
        String rest = line.substring(m.end());
        List<String> tok = tokenize(rest);
        if (tok.size() < 9) return null;
        try {
            String method          = tok.get(0);
            String uri             = tok.get(1);
            String protocol        = tok.get(2);
            int    status          = parseIntSafe(tok.get(3));
            // Use tget so that "-" (no body — e.g. 204/304) maps to null, not "0 B".
            String responseBytes   = tget(tok, 4);
            // %D (duration) and %F (TTFB) are normalised to ms. The divisor depends on the unit
            // Tomcat writes them in — configurable via log-viewer.access-log-time-unit (see
            // WebLogProperties.accessLogTimeUnit; default MICROS ⇒ /1000). VERIFY against your
            // Tomcat: if durations look 1000× too small, that property should be MILLIS.
            long   div             = timeDivisor.getAsLong();
            long   durMs           = parseLongSafe(tok.get(5)) / div;
            String ttfbStr         = tget(tok, 6);
            Long   ttfbMs          = ttfbStr != null ? parseLongSafe(ttfbStr) / div : null;
            String remoteIp        = tget(tok, 7);
            String xForwardedFor   = tget(tok, 8);
            String xRealIp         = tget(tok, 9);
            String vhost           = tget(tok, 10);
            String thread          = tget(tok, 11);
            String connFate        = tget(tok, 12);
            String host            = tget(tok, 13);
            String reqContentType  = tget(tok, 14);
            String reqContentLength= tget(tok, 15);
            String accept          = tget(tok, 16);
            String acceptEncoding  = tget(tok, 17);
            String acceptLanguage  = tget(tok, 18);
            String connection      = tget(tok, 19);
            String reqCacheControl = tget(tok, 20);
            String referer         = tget(tok, 22);
            String ua              = tget(tok, 23);
            String respContentType = tget(tok, 24);
            String respContentLength = tget(tok, 25);
            String respEncoding    = tget(tok, 26);
            String respCacheControl= tget(tok, 27);
            String requestId       = tget(tok, 28);
            String rateLimitLimit     = tget(tok, 29);
            String rateLimitRemaining = tget(tok, 30);
            String user               = tget(tok, 31);
            String auth               = tget(tok, 32);
            String deny               = tget(tok, 33);
            String attrValue          = tget(tok, 34);
            String attrName           = requestAttributeName.get();
            Map<String, String> attributes =
                    (attrValue != null && attrName != null && !attrName.isBlank())
                            ? Map.of(attrName, attrValue)
                            : Map.of();

            String fhs = forwardHeadersStrategy.get();
            boolean remoteIpIsTrusted = fhs != null && !"none".equalsIgnoreCase(fhs);
            String ip = remoteIpIsTrusted && remoteIp != null ? remoteIp
                      : xForwardedFor != null ? xForwardedFor.split(",")[0].trim()
                      : xRealIp      != null ? xRealIp
                      : remoteIp     != null ? remoteIp : "-";

            return new LogEntry(ts,
                    method, uri, status, durMs,
                    protocol, responseBytes, ip,
                    referer != null ? referer : "-",
                    ua != null ? ua : "-",
                    requestId != null ? requestId : "-",
                    null,
                    null, thread, null, null,
                    ttfbMs,
                    xForwardedFor, xRealIp, remoteIp,
                    vhost, connFate, host,
                    reqContentType, reqContentLength,
                    accept, acceptEncoding, acceptLanguage,
                    connection, reqCacheControl,
                    respContentType, respContentLength,
                    respEncoding, respCacheControl,
                    rateLimitLimit, rateLimitRemaining,
                    user, auth, deny, attributes);
        } catch (Exception e) {
            log.warn("parseTomcatAccess: failed to parse line: {}", line, e);
            return null;
        }
    }

    /**
     * Split a log line into tokens, honouring "double-quoted" groups. Handles {@code \"} escape
     * sequences inside quoted tokens so a User-Agent or URI containing a literal quote doesn't
     * shift all subsequent token indices.
     */
    static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        int n = s.length(), i = 0;
        StringBuilder buf = new StringBuilder();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '"') {
                // Scan forward respecting \" escapes.
                StringBuilder qbuf = new StringBuilder();
                int j = i + 1;
                while (j < n) {
                    char qc = s.charAt(j);
                    if (qc == '\\' && j + 1 < n && s.charAt(j + 1) == '"') {
                        qbuf.append('"');
                        j += 2;
                    } else if (qc == '"') {
                        j++;
                        break;
                    } else {
                        qbuf.append(qc);
                        j++;
                    }
                }
                out.add(qbuf.toString());
                i = j;
            } else if (Character.isWhitespace(c)) {
                if (!buf.isEmpty()) { out.add(buf.toString()); buf.setLength(0); }
                i++;
            } else {
                buf.append(c);
                i++;
            }
        }
        if (!buf.isEmpty()) out.add(buf.toString());
        return out;
    }

    // ── Tomcat catalina / error — plain server-log lines (logback pattern) ─────
    //   %d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] [%X{requestId:-}] %logger{50} - %msg%n
    // The [requestId] token is optional so older logs still parse. Stack-trace continuation lines
    // are appended to the previous entry's throwable.

    //noinspection RegExpRedundantEscape  -- \\] inside [^...] is redundant but kept for clarity
    private static final Pattern SERVER_LINE = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2}\\s\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+" +   // 1 timestamp
            "([A-Z]+)\\s+" +                                                  // 2 level
            "\\[([^\\]]+)\\]\\s+" +                                           // 3 [thread]
            "(?:\\[([^\\]]*)\\]\\s+)?" +                                      // 4 optional [requestId]
            "(\\S+)\\s+-\\s+" +                                               // 5 logger
            "(.*)$"                                                           // 6 message
    );

    static LogEntry serverEntry(String ts, String level, String thread, String logger,
                                String message, String requestId, String throwable) {
        return new LogEntry(ts,
                null, null, null, null,
                null, null, null, null, null,
                requestId, throwable,
                level, thread, logger, message,
                null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null,
                null, null,
                null, null, null, null);
    }

    static LogEntry attachStack(LogEntry base, StringBuilder stack) {
        String throwable = (stack != null && !stack.isEmpty()) ? stack.toString() : null;
        return serverEntry(base.timestamp(), base.level(), base.thread(), base.logger(),
                base.message(), base.requestId(), throwable);
    }

    /**
     * Parses raw server-log lines (newest-first) into entries, folding stack-trace continuation
     * lines into the entry above them. Reverses to chronological to attach continuations, then back
     * to newest-first to match the other readers. Stack traces never span files.
     */
    static List<LogEntry> parseServerLines(List<String> newestFirst) {
        List<String> raw = new ArrayList<>(newestFirst);
        Collections.reverse(raw);
        List<LogEntry> out = new ArrayList<>();
        StringBuilder stack = null;
        LogEntry pending = null;
        for (String line : raw) {
            Matcher m = SERVER_LINE.matcher(line);
            if (m.find()) {
                flushPending(pending, stack, out);
                String rid = m.group(4);
                if (rid == null || rid.isEmpty() || "-".equals(rid)) rid = null;
                pending = serverEntry(reformatTs(m.group(1)), m.group(2), m.group(3),
                        m.group(5), m.group(6), rid, null);
                stack = null;
            } else if (pending != null && !line.isBlank()) {
                if (stack == null) stack = new StringBuilder(line);
                else stack.append('\n').append(line);
            }
        }
        flushPending(pending, stack, out);
        Collections.reverse(out); // newest first, like other readers
        return out;
    }

    private static void flushPending(LogEntry e, StringBuilder stack, List<LogEntry> out) {
        if (e == null) return;
        out.add(attachStack(e, stack));
    }

    private static Integer parseIntSafe(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }
    private static Long parseLongSafe(String s) {
        if ("-".equals(s)) return 0L;
        try { return Long.parseLong(s); } catch (Exception e) { return 0L; }
    }

    private static final String[] MONTH_ABBR = {
        "Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"
    };

    // ── Forward streaming entry walks (used by paging) ─────────────────────────

    /** Streams a file's entries chronologically (top→bottom) with a per-file index (0 = oldest),
     *  dispatching to the server-log folding path or the one-entry-per-line path by type. Raw line
     *  I/O is delegated to {@link LogFileReader#streamLines}. */
    void streamEntries(File f, String type, EntryHandler h) throws IOException {
        if (isServerType(type)) { streamServerEntries(f, h); return; }
        Function<String, LogEntry> parse = "exclusions".equals(type) ? LogParser::parseExclusionLine
                                         : "slow".equals(type)        ? LogParser::parseSlowLine
                                                                      : this::parseTomcatAccess;
        int[] idx = {0};
        reader.streamLines(f, line -> {
            LogEntry e = parse.apply(line);
            return e == null || h.entry(idx[0]++, e);
        });
    }

    /** Server-log variant of {@link #streamEntries}: folds stack-trace continuation lines into the
     *  entry above them, emitting each entry (with its index) when the next header — or EOF — arrives. */
    void streamServerEntries(File f, EntryHandler h) throws IOException {
        final int[] idx = {0};
        final LogEntry[] pending = {null};
        final StringBuilder[] stack = {null};
        final boolean[] stopped = {false};
        reader.streamLines(f, line -> {
            Matcher m = SERVER_LINE.matcher(line);
            if (m.find()) {
                if (pending[0] != null && !h.entry(idx[0]++, attachStack(pending[0], stack[0]))) {
                    stopped[0] = true;
                    return false;
                }
                String rid = m.group(4);
                if (rid == null || rid.isEmpty() || "-".equals(rid)) rid = null;
                pending[0] = serverEntry(reformatTs(m.group(1)), m.group(2), m.group(3),
                        m.group(5), m.group(6), rid, null);
                stack[0] = null;
            } else if (pending[0] != null && !line.isBlank()) {
                if (stack[0] == null) stack[0] = new StringBuilder(line);
                else stack[0].append('\n').append(line);
            }
            return true;
        });
        if (!stopped[0] && pending[0] != null) {
            h.entry(idx[0]++, attachStack(pending[0], stack[0]));
        }
    }

    // ── Compact-JSON streams (exclusions, slow) ────────────────────────────────

    /** Parses one compact-JSON log line to a Map, or null if blank/unparseable. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJsonMap(String line) {
        if (line == null || line.isBlank()) return null;
        try {
            return OBJECT_MAPPER.readValue(line, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** Request-side fields shared by the compact-JSON parsers, beyond the always-present core. */
    private record CompactMeta(String thread, String protocol, String host,
                               String xForwardedFor, String userAgent, String referer) {
        static final CompactMeta NONE = new CompactMeta(null, null, null, null, null, null);
    }

    /**
     * Builds a request-style {@link LogEntry} from a parsed compact-JSON log map — shared by the
     * {@code exclusions} and {@code slow} parsers, which carry the same core and differ only in the
     * caller-supplied {@code durationMs} and the {@link CompactMeta} extras. Response-side fields
     * stay null.
     */
    private static LogEntry compactJsonEntry(Map<String, Object> m, long durationMs, CompactMeta meta) {
        String ts        = reformatTs((String) m.get("ts"));
        String method    = (String)  m.getOrDefault("method",    "-");
        String uri       = (String)  m.getOrDefault("uri",       "-");
        int    status    = ((Number) m.getOrDefault("status",    0)).intValue();
        String ip        = (String)  m.getOrDefault("ip",        "-");
        String requestId = (String)  m.getOrDefault("requestId", null);
        // AccessLogExclusionFilter writes these three only when present, so they are
        // absent (null) on most lines and on every `slow` entry.
        String user      = (String)  m.getOrDefault("user",      null);
        String auth      = (String)  m.getOrDefault("auth",      null);
        String deny      = (String)  m.getOrDefault("deny",      null);
        return new LogEntry(ts,
                method, uri, status, durationMs,
                meta.protocol(), null, ip, meta.referer(), meta.userAgent(), requestId, null,
                null, meta.thread(), null, null,
                null, meta.xForwardedFor(), null, null, null, null, meta.host(),
                null, null, null, null, null, null, null,
                null, null, null, null,
                null, null,
                user, auth, deny, null);
    }

    static LogEntry parseExclusionLine(String line) {
        Map<String, Object> m = readJsonMap(line);
        if (m == null) return null;
        try {
            long durationMs = ((Number) m.getOrDefault("durationMs", 0L)).longValue();
            return compactJsonEntry(m, durationMs, CompactMeta.NONE);
        } catch (Exception e) {
            return null;
        }
    }

    static LogEntry parseSlowLine(String line) {
        Map<String, Object> m = readJsonMap(line);
        if (m == null) return null;
        try {
            long inFlightMs = ((Number) m.getOrDefault("inFlightMs", 0L)).longValue();
            CompactMeta meta = new CompactMeta(
                    (String) m.getOrDefault("thread", null),
                    (String) m.getOrDefault("protocol", null),
                    (String) m.getOrDefault("host", null),
                    (String) m.getOrDefault("xForwardedFor", null),
                    (String) m.getOrDefault("ua", null),
                    (String) m.getOrDefault("referer", null));
            return compactJsonEntry(m, inFlightMs, meta);
        } catch (Exception e) {
            return null;
        }
    }

    /** Converts "yyyy-MM-dd HH:mm:ss.SSS" → "dd-MMM-yyyy HH:mm:ss.SSS". */
    static String reformatTs(String ts) {
        if (ts == null || ts.length() < 10) return ts;
        try {
            int yr   = Integer.parseInt(ts.substring(0, 4));
            int mo   = Integer.parseInt(ts.substring(5, 7));
            int dy   = Integer.parseInt(ts.substring(8, 10));
            String rest = ts.length() > 11 ? " " + ts.substring(11) : "";
            return String.format("%02d-%s-%04d%s", dy, MONTH_ABBR[mo - 1], yr, rest);
        } catch (Exception e) { return ts; }
    }
}
