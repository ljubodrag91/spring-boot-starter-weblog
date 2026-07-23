package com.eventhorizon.weblog.controller;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.eventhorizon.weblog.WebLogProperties;
import com.eventhorizon.weblog.inflight.InFlightRegistry;
import com.eventhorizon.weblog.inflight.InFlightRequest;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.unit.DataSize;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/admin/logs")
@Slf4j
public class LogViewerController {

    @Value("${log-viewer.access-log-directory:logs}")
    private String logDir;

    // Name of the trailing access-log request attribute (see WebLogProperties.requestAttribute).
    // The parsed value is emitted in the /data JSON under this key. Empty (feature off) by default,
    // mirroring WebLogProperties.requestAttribute — keep the two defaults in sync.
    @Value("${log-viewer.request-attribute:}")
    private String requestAttributeName = "";

    // Largest compressed log file the viewer will decompress (see WebLogProperties). Parsed
    // with DataSize (e.g. "50MB"). Files larger than this are skipped in tailLinesGzip().
    @Value("${log-viewer.max-compressed-read-bytes:50MB}")
    private String maxCompressedReadBytes = "50MB";

    // Mirrors the consuming app's own forward-headers config. When set (e.g. "native" via
    // Tomcat's RemoteIpValve, or "framework"), the app only trusts X-Forwarded-For from a
    // configured proxy — meaning %a (remoteIp) is already the resolved, trustworthy client
    // IP and should win over the raw header. Left at Spring Boot's own default ("none"),
    // behavior is unchanged from before this field existed.
    @Value("${server.forward-headers-strategy:none}")
    private String forwardHeadersStrategy;

    // Live in-flight request view (see spec/live-inflight-view.md). The registry is written by
    // InFlightRequestFilter and read here read-only for GET /admin/logs/inflight; WebLogProperties
    // supplies the slow threshold (amber cutoff) and the view's enabled/refresh settings. Field
    // injection matches this controller's @Value style (it is @Bean-constructed, so the autowiring
    // post-processor still populates these).
    @Autowired
    private InFlightRegistry inFlightRegistry;
    @Autowired
    private WebLogProperties webLogProperties;

    @Value("classpath:views/log-viewer.html")
    private Resource htmlResource;

    // The page's CSS and JS live in their own files (served as static assets by cssAsset()/jsAsset())
    // rather than inlined, for maintainability. Only the HTML carries server-side [[..]] placeholders;
    // the JS reads its per-request bootstrap from an inline window.__INIT object instead.
    @Value("classpath:views/log-viewer.css")
    private Resource cssResource;
    // The viewer script is split by concern into four source files (core → render → modal → ui) for
    // maintainability. They are concatenated IN THIS ORDER into one served script (see init()), so at
    // runtime it is a single classic script with one shared scope — identical to the former monolith.
    // Order matters: later parts reference declarations from earlier ones, and the init() IIFE is last.
    @Value("classpath:views/log-viewer.core.js")
    private Resource jsCoreResource;
    @Value("classpath:views/log-viewer.render.js")
    private Resource jsRenderResource;
    @Value("classpath:views/log-viewer.modal.js")
    private Resource jsModalResource;
    @Value("classpath:views/log-viewer.ui.js")
    private Resource jsUiResource;

    private String htmlTemplate;
    private String cssContent, jsContent;
    private String cssEtag, jsEtag;

    /** File-I/O layer (discovery, tailing, gzip, streaming); built in {@link #init()} once the
     *  {@code @Value} fields it needs (log dir, compressed-read cap) are populated. */
    private LogFileReader reader;

    /** Text→{@link LogEntry} parsing layer; reads its live config (attribute name, forward-headers
     *  strategy, time-unit divisor) back through this controller's fields via accessors. */
    private LogParser parser;

    @PostConstruct
    void init() throws IOException {
        htmlTemplate = readAll(htmlResource);
        cssContent   = readAll(cssResource);
        // Concatenate the four script parts into one served asset (see field javadoc for why).
        jsContent    = readAll(jsCoreResource) + readAll(jsRenderResource)
                     + readAll(jsModalResource) + readAll(jsUiResource);
        cssEtag = etag(cssContent);
        jsEtag  = etag(jsContent);
        reader = new LogFileReader(logDir, DataSize.parse(maxCompressedReadBytes).toBytes());
        parser = new LogParser(reader,
                () -> requestAttributeName,
                () -> forwardHeadersStrategy,
                this::accessLogTimeDivisor);
        warnIfUnprotected();
    }

    /**
     * Logs a loud startup warning when the viewer is enabled but Spring Security is not on the
     * classpath — in that case {@code WebLogSecurityAutoConfiguration} cannot activate and
     * {@code /admin/logs/**} (client IPs, stack traces, full request history) is served with no
     * authentication. The exposure is easy to miss otherwise; this makes it explicit.
     */
    private void warnIfUnprotected() {
        boolean securityPresent = org.springframework.util.ClassUtils.isPresent(
                "org.springframework.security.web.SecurityFilterChain",
                LogViewerController.class.getClassLoader());
        if (!securityPresent) {
            log.warn("SECURITY: the log viewer at /admin/logs/** is UNAUTHENTICATED — Spring "
                    + "Security is not on the classpath, so it exposes client IPs, stack traces, and "
                    + "full request history to anyone who can reach this port. Add "
                    + "spring-boot-starter-security (a fallback HTTP Basic chain then protects it), "
                    + "place it behind your own auth/proxy, or disable it with log-viewer.enabled=false.");
        }
    }

    private static String readAll(Resource r) throws IOException {
        return new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    /** Strong, content-derived ETag: the first 8 bytes of the content's SHA-256, hex-encoded. A
     *  cryptographic digest avoids the collisions possible with {@code String.hashCode()} (which
     *  could serve a stale asset after an upgrade). Stable per build; changes exactly when the
     *  asset content does (busting caches on upgrade). */
    private static String etag(String content) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(18).append('"');
            for (int i = 0; i < 8; i++) sb.append(Character.forDigit((d[i] >> 4) & 0xF, 16))
                                          .append(Character.forDigit(d[i] & 0xF, 16));
            return sb.append('"').toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every JVM; fall back defensively.
            return "\"" + Integer.toHexString(content.hashCode()) + "\"";
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Routes
    // ────────────────────────────────────────────────────────────────────────

    @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> view(
            HttpServletRequest request,
            @RequestParam(defaultValue = "access") String type,
            @RequestParam(defaultValue = "5000") int lines) {
        lines = snapToValidOption(lines);
        // Do NOT embed log data in the HTML — at 5 000 lines the payload can exceed
        // 2 MB, requiring a huge in-memory string just to serve the shell page.
        // The frontend's loadInitial() detects null INIT_DATA and calls fetchData()
        // which streams the data via the /data endpoint (with a visible loading spinner).
        // snapToValidType() is essential, not cosmetic: `type` is reflected verbatim into
        // an inline <script> string (const INIT_TYPE = '...') in the template. Without the
        // whitelist a crafted value (e.g. ');alert(1);//) would break out and execute —
        // a reflected XSS running in the authenticated admin's session.
        String html = htmlTemplate
                .replace("[[CTX]]",      request.getContextPath())
                .replace("[[TYPE]]",     snapToValidType(type))
                .replace("[[LINES]]",    String.valueOf(lines))
                .replace("[[REQ_ATTR]]", safeReqAttr())
                // Live in-flight strip config — both are boolean/numeric, safe to inline into the script.
                .replace("[[INFLIGHT_ENABLED]]",    String.valueOf(webLogProperties.isInflightViewEnabled()))
                .replace("[[INFLIGHT_REFRESH_MS]]", String.valueOf(webLogProperties.getInflightRefresh().toMillis()))
                .replace("[[DATA]]",     "null");
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header("Cache-Control", "no-store")
                .body(html);
    }

    /** The viewer's stylesheet. Static, cacheable (revalidated via ETag); no server placeholders. */
    @GetMapping("/log-viewer.css")
    public ResponseEntity<String> cssAsset(
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        return asset(cssContent, cssEtag, "text/css", ifNoneMatch);
    }

    /** The viewer's script. Static, cacheable (revalidated via ETag); reads window.__INIT for config. */
    @GetMapping("/log-viewer.js")
    public ResponseEntity<String> jsAsset(
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        return asset(jsContent, jsEtag, "text/javascript", ifNoneMatch);
    }

    /** Serves a cached static asset with an ETag; returns 304 when the client's copy is current. */
    private static ResponseEntity<String> asset(String body, String tag, String contentType, String ifNoneMatch) {
        if (tag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(tag).cacheControl(CacheControl.noCache()).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType + ";charset=UTF-8"))
                .cacheControl(CacheControl.noCache())
                .eTag(tag)
                .body(body);
    }

    @GetMapping(value = "/data", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<LogEntry>> data(
            @RequestParam(defaultValue = "access") String type,
            @RequestParam(defaultValue = "5000") int lines) {
        lines = snapToValidOption(lines);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(readLog(type, lines));
    }

    /**
     * Live snapshot of requests in flight right now — the read side of {@link InFlightRegistry}.
     *
     * <p>Unlike every other endpoint here (which read completed log lines from disk), this reflects
     * requests that have <b>not finished</b>: it is the only place a still-running or never-completing
     * request is visible, since the Tomcat access log writes only at completion. Rows are newest-longest
     * first; {@code slow=true} marks any past {@code log-viewer.slow-request-threshold} (the frontend
     * colours those amber). {@code count} is the registry size — normally bounded by the Tomcat worker
     * pool, so a value climbing far beyond it would signal a leak (there is deliberately no eviction cap).
     *
     * <p>Read-only and cheap (serialises an in-memory map; no disk I/O). Served under the same
     * {@code /admin/logs/**} auth as the rest of the viewer. Because the frontend polls this every
     * {@code inflight-refresh} (default 3s) whenever the viewer is open, its path is a default
     * {@code log-viewer.silent-prefixes} entry, so the poll is suppressed from the access log,
     * {@code exclusions.log}, and the in-flight registry alike — no self-logging, no feedback loop.
     */
    @GetMapping(value = "/inflight", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<InFlightView> inflight() {
        long thresholdMs = webLogProperties.getSlowRequestThreshold().toMillis();
        String now = INFLIGHT_TS.format(Instant.now());
        List<InFlightRow> rows = new ArrayList<>();
        if (webLogProperties.isInflightViewEnabled() && inFlightRegistry != null) {
            long nowNanos = System.nanoTime();
            for (InFlightRequest r : inFlightRegistry.snapshot()) {
                long ms = (nowNanos - r.getStartNanos()) / 1_000_000L;
                rows.add(new InFlightRow(r.getRequestId(), r.getMethod(), r.getUri(), r.getIp(),
                        r.getThread(), INFLIGHT_TS.format(Instant.ofEpochMilli(r.getStartEpochMs())),
                        ms, ms >= thresholdMs));
            }
            rows.sort(Comparator.comparingLong(InFlightRow::inFlightMs).reversed());
        }
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(new InFlightView(now, thresholdMs, rows.size(), rows));
    }

    /**
     * Returns the {@code error.log} entries whose {@code requestId} matches and that carry a
     * stack trace — the server side of "click a 5xx access-log entry, see its stack trace".
     * The access log and the error log share the {@code X-Request-Id} value, so this joins
     * them by that id. {@code requestId} is only ever compared against parsed log entries;
     * it never touches the filesystem, so there is no path-traversal surface.
     *
     * <p>Optional {@code date} (yyyy-MM-dd, the clicked row's date) scopes the search to that
     * day's error file(s) ± one day for a midnight boundary, reading {@code .gz} archives too —
     * so a trace is found even for an error that has already rotated and compressed. Without
     * {@code date} it falls back to scanning the newest {@code lines} error entries.
     */
    @GetMapping(value = "/trace", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<LogEntry>> trace(
            @RequestParam String requestId,
            @RequestParam(required = false) String date,
            @RequestParam(defaultValue = "5000") int lines) {
        lines = snapToValidOption(lines);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(findByRequestId(requestId, parseDateParam(date), lines));
    }

    /**
     * Paged, date-ranged reads — the server side of "browse back into history".
     *
     * <p>Unlike {@link #data} (which returns the newest N lines as a live tail), this returns one
     * bounded page positioned by {@code cursor}+{@code dir}, optionally constrained to a date
     * window via {@code from}/{@code to} (yyyy-MM-dd; files are selected by the date in their name).
     * Memory stays bounded to {@code lines} entries regardless of how large or how many files the
     * window spans — files are opened lazily, newest-first, one at a time, only until the page is
     * filled. {@code .gz} archives are read live (no temp files) and are NOT subject to the
     * {@code max-compressed-read-bytes} skip that guards the tail view.
     *
     * @param cursorFile  file name of the boundary entry (echoed from a prior page's cursor);
     *                    validated against the discovered file set, so no path-traversal surface
     * @param cursorIndex per-file chronological index of the boundary entry ({@code -1} / absent
     *                    ⇒ start at the newest end)
     * @param dir         {@code "older"} (default) or {@code "newer"}
     */
    @GetMapping(value = "/page", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LogPage> page(
            @RequestParam(defaultValue = "access") String type,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String cursorFile,
            @RequestParam(defaultValue = "-1") int cursorIndex,
            @RequestParam(defaultValue = "older") String dir,
            @RequestParam(defaultValue = "5000") int lines) {
        String safeType = snapToValidType(type);
        int pageSize = snapToPageSize(lines);
        boolean older = !"newer".equalsIgnoreCase(dir);
        LogPage p = readPage(safeType, parseDateParam(from), parseDateParam(to),
                cursorFile, cursorIndex, older, pageSize);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(p);
    }

    /** Valid "last N lines" choices that match the frontend dropdown options. */
    private static final int[] VALID_LINE_OPTIONS = {2000, 5000, 10000, 20000};

    /**
     * Snaps {@code lines} to the nearest value in {@link #VALID_LINE_OPTIONS}.
     * Keeps the frontend dropdown and the server response size in sync — a value
     * not in the list would be injected into {@code INIT_LINES} but wouldn't match
     * any {@code <option>}, leaving {@code linesEl.value === ""} and causing the
     * live-tail fetch to request {@code ?lines=0}.
     */
    private static int snapToValidOption(int lines) {
        int best = VALID_LINE_OPTIONS[0];
        int bestDist = Math.abs(lines - best);
        for (int opt : VALID_LINE_OPTIONS) {
            int dist = Math.abs(lines - opt);
            if (dist < bestDist) { best = opt; bestDist = dist; }
        }
        return best;
    }

    /**
     * Hard ceiling on a single paged response ({@link #page}). Matches the tail view's largest
     * option ({@code 20 000}); a paged read may inflate whole archives, so a bounded page keeps
     * per-request heap (~{@code pageSize × entrySize}) and latency in check, especially under
     * concurrent viewers.
     */
    private static final int MAX_PAGE_SIZE = 20_000;

    /** Snaps to a valid option, then caps at {@link #MAX_PAGE_SIZE} (a defensive belt-and-braces
     *  cap now that the largest option equals the ceiling). */
    private static int snapToPageSize(int lines) {
        return Math.min(snapToValidOption(lines), MAX_PAGE_SIZE);
    }

    /**
     * Parses a {@code yyyy-MM-dd} (or ISO datetime, of which only the date is used) request
     * parameter to a {@link LocalDate}, or {@code null} if absent/blank/unparseable. Day
     * granularity is deliberate: {@code from}/{@code to} select whole day-files by the date in
     * their name; finer narrowing is done client-side by the histogram brush.
     */
    private static LocalDate parseDateParam(String s) {
        if (s == null || s.isBlank() || s.length() < 10) return null;
        try {
            return LocalDate.parse(s.substring(0, 10), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The only log types the viewer serves — mirrors the switch in {@link #readLog}. */
    private static final Set<String> VALID_TYPES = Set.of("access", "catalina", "error", "exclusions", "slow");

    /**
     * Returns {@code type} if it is a recognised log type, else {@code "access"}.
     *
     * <p>Security-critical: {@code type} is substituted into an inline {@code <script>}
     * string literal in the served HTML. Restricting it to a fixed allow-list is what
     * prevents a reflected XSS via the {@code ?type=} query parameter.
     */
    private static String snapToValidType(String type) {
        return VALID_TYPES.contains(type) ? type : "access";
    }

    /**
     * The configured request-attribute name, substituted into an inline {@code <script>}
     * string in the template ({@code const REQ_ATTR = '...'}). Restricted to a conservative
     * identifier charset so a misconfigured value cannot break out of the JS literal (same
     * class of concern as the {@code type} param). Blank ⇒ the viewer shows no such column.
     */
    private String safeReqAttr() {
        String a = requestAttributeName;
        return (a != null && a.matches("[A-Za-z0-9_.-]+")) ? a : "";
    }

    // ────────────────────────────────────────────────────────────────────────
    // Domain model — one record covering both styles. Nulls mark absent fields.
    // ────────────────────────────────────────────────────────────────────────

    public record LogEntry(
            String timestamp,
            // request-style fields
            String method, String uri, Integer status, Long durationMs,
            // responseBytes = %b (bytes sent in the response body). Named for what it holds; it is
            // NOT the request size (that is reqContentLength).
            String protocol, String responseBytes, String ip, String referer, String ua,
            String requestId, String throwable,
            // server-style fields (catalina / error logs)
            String level, String thread, String logger, String message,
            // Tomcat access log extended fields
            Long   ttfbMs,
            String xForwardedFor, String xRealIp, String remoteIp,
            String vhost, String connFate, String host,
            String reqContentType, String reqContentLength,
            String accept, String acceptEncoding, String acceptLanguage,
            String connection, String reqCacheControl,
            String respContentType, String respContentLength,
            String respEncoding, String respCacheControl,
            String rateLimitLimit, String rateLimitRemaining,
            // Authenticated principal (AccessLogExclusionFilter.USER_REQ_ATTR), the safe
            // Authorization-header summary and the consumer-set deny reason
            // (AuthInfoFilter.AUTH_REQ_ATTR / DENY_REQ_ATTR). Null when absent.
            String user, String auth, String deny,
            // Configurable per-request attribute (default key "apiKey"), flattened into the
            // JSON object under its configured name via @JsonAnyGetter — so the output key
            // follows log-viewer.request-attribute instead of being hardcoded. Empty for
            // server/exclusion entries. Never null (normalized by the compact constructor).
            @JsonAnyGetter Map<String, String> attributes
    ) {
        public LogEntry {
            if (attributes == null) attributes = Map.of();
        }
    }

    /**
     * Opaque position in a log type's virtual (all-files) stream: the {@code file} the boundary
     * entry lives in and its per-file chronological {@code index} (0 = oldest entry in that file).
     * Stable because archives are write-once. The client echoes it back to page further; the
     * server always resolves {@code file} against its own discovered file set, never as a path.
     */
    public record Cursor(String file, int index) {}

    /**
     * One page of {@link #page} results. {@code entries} are newest-first. {@code oldest}/
     * {@code newest} are the cursors of the last/first entry so the client can ask for the next
     * page in either direction; {@code hasOlder}/{@code hasNewer} say whether such a page exists.
     */
    public record LogPage(
            List<LogEntry> entries,
            Cursor oldest, Cursor newest,
            boolean hasOlder, boolean hasNewer
    ) {}

    /** Timestamp format for the in-flight snapshot (system zone), matching the pre-reformat log style. */
    private static final DateTimeFormatter INFLIGHT_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    /**
     * Live in-flight snapshot returned by {@link #inflight()}. {@code now} is the server clock at
     * snapshot time; {@code thresholdMs} is the slow cutoff (for amber highlighting); {@code count}
     * is the registry size (leak signal); {@code requests} are the current in-flight rows, longest first.
     */
    public record InFlightView(String now, long thresholdMs, int count, List<InFlightRow> requests) {}

    /** One in-flight request. {@code inFlightMs} is server-computed age; {@code slow} = past threshold. */
    public record InFlightRow(String requestId, String method, String uri, String ip,
                              String thread, String startedAt, long inFlightMs, boolean slow) {}

    /** Internal: an entry tagged with its source file + per-file index while assembling a page. */
    private record IndexedEntry(String file, int index, LogEntry entry) {}

    // ────────────────────────────────────────────────────────────────────────
    // Dispatcher
    // ────────────────────────────────────────────────────────────────────────

    private List<LogEntry> readLog(String type, int maxLines) {
        try {
            return switch (type) {
                case "catalina"   -> readServerByPrefix("catalina", maxLines);
                case "error"      -> readServerByPrefix("error",    maxLines);
                case "exclusions" -> readExclusions(maxLines);
                case "slow"       -> readSlow(maxLines);
                default           -> readTomcatAccess(maxLines);
            };
        } catch (Exception e) {
            log.warn("Failed to read log type={}", type, e);
            return Collections.emptyList();
        }
    }

    /**
     * Finds {@code error} entries whose parsed {@code requestId} matches and that carry a stack
     * trace. When {@code date} is given, scopes the search to that day's error file(s) ± one day
     * (covering an error logged just across a midnight boundary), streaming {@code .gz} archives
     * too — so a trace resolves even for an already-compressed error. When {@code date} is null,
     * falls back to the newest {@code maxLines} error entries. Possibly empty.
     */
    private List<LogEntry> findByRequestId(String requestId, LocalDate date, int maxLines) {
        if (requestId == null || requestId.isBlank()) return Collections.emptyList();
        List<LogEntry> out = new ArrayList<>();
        try {
            if (date == null) {
                for (LogEntry e : readServerByPrefix("error", maxLines)) {
                    if (matchesTrace(e, requestId)) out.add(e);
                }
            } else {
                // Scope to error files around the given day; stream and collect only matches.
                for (File f : reader.selectFiles("error", date.minusDays(1), date.plusDays(1))) {
                    if (out.size() >= maxLines) break;
                    parser.streamServerEntries(f, (idx, e) -> {
                        if (matchesTrace(e, requestId)) out.add(e);
                        return out.size() < maxLines;
                    });
                }
            }
        } catch (Exception e) {
            log.warn("findByRequestId failed for requestId={}", requestId, e);
        }
        return out;
    }

    private static boolean matchesTrace(LogEntry e, String requestId) {
        return requestId.equals(e.requestId()) && e.throwable() != null && !e.throwable().isBlank();
    }

    /** Reads server-style logs (catalina / error), including compressed {@code .log.gz} archives. */
    private List<LogEntry> readServerByPrefix(String prefix, int maxLines) {
        return reader.readByPrefix(prefix, maxLines, LogParser::parseServerLines);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Paged / date-ranged reads — forward streaming (via LogParser) with bounded memory
    // ────────────────────────────────────────────────────────────────────────

    private static String prefixForType(String type) {
        return switch (type) {
            case "catalina"   -> "catalina";
            case "error"      -> "error";
            case "exclusions" -> "exclusions";
            case "slow"       -> "slow";
            default           -> "access_log";
        };
    }

    // ── page assembly ────────────────────────────────────────────────────────

    /** Reads one page positioned by cursor+direction, scoped to {@code [from,to]}. Never throws —
     *  returns an empty page on error. Memory bounded to {@code pageSize} entries throughout. */
    private LogPage readPage(String type, LocalDate from, LocalDate to,
                             String cursorFile, int cursorIndex, boolean older, int pageSize) {
        try {
            File[] sel = reader.selectFiles(prefixForType(type), from, to); // newest-first
            if (sel.length == 0) return new LogPage(List.of(), null, null, false, false);
            return older ? olderPage(type, sel, cursorFile, cursorIndex, pageSize)
                         : newerPage(type, sel, cursorFile, cursorIndex, pageSize);
        } catch (Exception e) {
            log.warn("readPage failed type={} dir={}", type, older ? "older" : "newer", e);
            return new LogPage(List.of(), null, null, false, false);
        }
    }

    /** The {@code pageSize} entries immediately older than the cursor (newest-first). With no
     *  cursor this is the newest page of the selected set. */
    private LogPage olderPage(String type, File[] sel, String cursorFile, int cursorIndex, int pageSize)
            throws IOException {
        int startFileIdx = 0;
        int boundary = Integer.MAX_VALUE; // exclusive upper bound within the start file
        if (cursorFile != null) {
            int fi = LogFileReader.indexOfFile(sel, cursorFile);
            if (fi >= 0) { startFileIdx = fi; boundary = cursorIndex; } // else: stale cursor → newest page
        }
        // Probe one entry beyond the page so hasOlder reflects whether a further page actually
        // exists — not merely whether this one came back full. A full-page proxy is wrong when the
        // remaining count is an exact multiple of pageSize: it keeps the button enabled and the next
        // click lands on a spurious empty page. The extra entry is trimmed before returning.
        final int probe = pageSize + 1;
        List<IndexedEntry> page = new ArrayList<>();
        for (int fiOuter = startFileIdx; fiOuter < sel.length && page.size() < probe; fiOuter++) {
            final File f = sel[fiOuter];
            final int limit = (fiOuter == startFileIdx) ? boundary : Integer.MAX_VALUE;
            final int cap = probe - page.size();
            final ArrayDeque<IndexedEntry> window = new ArrayDeque<>(); // last `cap` with index < limit
            parser.streamEntries(f, type, (idx, e) -> {
                if (idx >= limit) return false; // reached boundary; all further entries are newer
                window.addLast(new IndexedEntry(f.getName(), idx, e));
                if (window.size() > cap) window.removeFirst();
                return true;
            });
            IndexedEntry[] arr = window.toArray(new IndexedEntry[0]);
            for (int k = arr.length - 1; k >= 0; k--) page.add(arr[k]); // newest-first
        }
        boolean hasOlder = page.size() > pageSize;
        if (hasOlder) page.remove(page.size() - 1); // drop the probe (oldest); it starts the next older page
        return buildPage(page, hasOlder, /*hasNewer*/ cursorFile != null);
    }

    /** The {@code pageSize} entries immediately newer than the cursor (newest-first). */
    private LogPage newerPage(String type, File[] sel, String cursorFile, int cursorIndex, int pageSize)
            throws IOException {
        int startFileIdx = cursorFile == null ? -1 : LogFileReader.indexOfFile(sel, cursorFile);
        if (startFileIdx < 0) return olderPage(type, sel, null, -1, pageSize); // no/stale cursor → newest page

        // Collect chronologically ascending (closest-newer first), then present newest-first.
        // sel is newest-first, so newer files sit at LOWER indices → iterate startFileIdx down to 0.
        // Probe one entry beyond the page so hasNewer reflects whether a further page actually exists
        // rather than merely whether this one came back full — a full-page proxy is wrong at exact
        // multiples of pageSize and yields a spurious empty page on the next click. Trimmed below.
        final int probe = pageSize + 1;
        List<IndexedEntry> collected = new ArrayList<>();
        for (int fiOuter = startFileIdx; fiOuter >= 0 && collected.size() < probe; fiOuter--) {
            final File f = sel[fiOuter];
            final int lower = (fiOuter == startFileIdx) ? cursorIndex : -1; // exclusive lower bound
            final int cap = probe - collected.size();
            final List<IndexedEntry> fileCollected = new ArrayList<>();
            parser.streamEntries(f, type, (idx, e) -> {
                if (idx <= lower) return true; // skip up to and including the cursor
                fileCollected.add(new IndexedEntry(f.getName(), idx, e));
                return fileCollected.size() < cap;
            });
            collected.addAll(fileCollected);
        }
        boolean hasNewer = collected.size() > pageSize;
        // collected is ascending, so the probe is the newest entry (last); drop it — it starts the
        // next newer page and keeps this page contiguous with the following one.
        if (hasNewer) collected.remove(collected.size() - 1);
        Collections.reverse(collected); // newest-first
        return buildPage(collected, /*hasOlder*/ true, hasNewer);
    }

    private static LogPage buildPage(List<IndexedEntry> page, boolean hasOlder, boolean hasNewer) {
        if (page.isEmpty()) return new LogPage(List.of(), null, null, hasOlder, hasNewer);
        List<LogEntry> entries = new ArrayList<>(page.size());
        for (IndexedEntry ie : page) entries.add(ie.entry());
        IndexedEntry newest = page.get(0);
        IndexedEntry oldest = page.get(page.size() - 1);
        return new LogPage(entries,
                new Cursor(oldest.file(), oldest.index()),
                new Cursor(newest.file(), newest.index()),
                hasOlder, hasNewer);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Per-type reads — file discovery/tailing is delegated to LogFileReader, and
    // line→LogEntry parsing to LogParser. This controller only orchestrates.
    // ────────────────────────────────────────────────────────────────────────

    private List<LogEntry> readTomcatAccess(int maxLines) {
        return reader.readByPrefix("access_log", maxLines,
                lines -> LogParser.mapLines(lines, parser::parseTomcatAccess));
    }

    private List<LogEntry> readExclusions(int maxLines) {
        return reader.readByPrefix("exclusions", maxLines,
                lines -> LogParser.mapLines(lines, LogParser::parseExclusionLine));
    }

    private List<LogEntry> readSlow(int maxLines) {
        return reader.readByPrefix("slow", maxLines,
                lines -> LogParser.mapLines(lines, LogParser::parseSlowLine));
    }

    /**
     * Divisor that converts a raw {@code %D}/{@code %F} token to milliseconds, per
     * {@code log-viewer.access-log-time-unit}: 1000 for {@code MICROS} (default), 1 for
     * {@code MILLIS}. Passed to {@link LogParser} as a live accessor; null-safe so parse-only unit
     * tests without wired properties keep the historical {@code MICROS} behaviour.
     */
    private long accessLogTimeDivisor() {
        return (webLogProperties != null
                && webLogProperties.getAccessLogTimeUnit() == WebLogProperties.AccessLogTimeUnit.MILLIS)
                ? 1L : 1000L;
    }

    // Thin delegators to LogParser, retained so the parsing unit tests can drive parsing through
    // this controller (which owns the live config the parser reads). The implementations live in
    // LogParser; these just forward.
    private LogEntry parseTomcatAccess(String line) { return parser.parseTomcatAccess(line); }
    private LogEntry parseExclusionLine(String line) { return LogParser.parseExclusionLine(line); }
}
