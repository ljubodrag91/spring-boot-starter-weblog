package com.eventhorizon.weblog.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/admin/logs")
@Slf4j
public class LogViewerController {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Value("${log-viewer.access-log-directory:logs}")
    private String logDir;

    @Value("classpath:views/log-viewer.html")
    private Resource htmlResource;

    private String htmlTemplate;

    @PostConstruct
    void init() throws IOException {
        htmlTemplate = new String(htmlResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
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
        String html = htmlTemplate
                .replace("[[CTX]]",    request.getContextPath())
                .replace("[[TYPE]]",   type)
                .replace("[[LINES]]",  String.valueOf(lines))
                .replace("[[DATA]]",   "null");
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header("Cache-Control", "no-store")
                .body(html);
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
     * Returns the captured request/response bodies for a single request ID, or
     * an empty object if no entry matches. Only available when
     * {@code log-viewer.body.enabled=true} — otherwise the bodies file does not
     * exist and the endpoint returns {@code {}}.
     *
     * <p>Scans newest-first across {@code bodies.*.log}/{@code bodies.*.log.gz}
     * files until the first line whose {@code "requestId":"…"} substring matches.
     * Bodies are typically queried while debugging an entry currently visible in
     * the log table, so the match is almost always within the active file.
     */
    @GetMapping(value = "/body", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> body(@RequestParam("requestId") String requestId) {
        String json = findBodyEntry(requestId);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(json != null ? json : "{}");
    }

    /** Valid "last N lines" choices that match the frontend dropdown options. */
    private static final int[] VALID_LINE_OPTIONS = {2000, 5000, 10000, 20000, 50000};

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

    // ────────────────────────────────────────────────────────────────────────
    // Domain model — one record covering both styles. Nulls mark absent fields.
    // ────────────────────────────────────────────────────────────────────────

    public record LogEntry(
            String timestamp,
            // request-style fields
            String method, String uri, Integer status, Long durationMs,
            String protocol, String requestSize, String ip, String referer, String ua,
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
            // Spring Security principal — only populated for exclusion-log entries.
            String user,
            // Safe Authorization summary (AuthInfoFilter) and consumer-set deny reason.
            String auth, String deny
    ) {}

    // ────────────────────────────────────────────────────────────────────────
    // Dispatcher
    // ────────────────────────────────────────────────────────────────────────

    private List<LogEntry> readLog(String type, int maxLines) {
        try {
            return switch (type) {
                case "catalina"   -> readServerByPrefix("catalina", maxLines);
                case "error"      -> readServerByPrefix("error",    maxLines);
                case "exclusions" -> readExclusions(maxLines);
                default           -> readTomcatAccess(maxLines);
            };
        } catch (Exception e) {
            log.warn("Failed to read log type={}", type, e);
            return Collections.emptyList();
        }
    }

    /**
     * Finds log files whose names match {@code <prefix>.log} (fixed-name legacy format,
     * used when Logback's {@code <file>} option is set) or {@code <prefix>.*.log} /
     * {@code <prefix>.*.log.gz} (dated format, used when {@code <file>} is omitted).
     * Returns files sorted newest-first; lexicographic order on the date-bearing names
     * is chronological.
     */
    private File[] findLogFiles(File dir, String prefix) {
        File[] files = dir.listFiles(f -> {
            String n = f.getName();
            return n.equals(prefix + ".log")
                    || (n.startsWith(prefix + ".")
                        && (n.endsWith(".log") || n.endsWith(".log.gz")));
        });
        if (files == null) return new File[0];
        Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
        return files;
    }

    /** Reads server-style log (catalina / error) using pattern-based file discovery. */
    private List<LogEntry> readServerByPrefix(String prefix, int maxLines) {
        File dir = new File(logDir);
        if (!dir.exists() || !dir.isDirectory()) return Collections.emptyList();
        File[] candidates = findLogFiles(dir, prefix);
        // Read only the most recent uncompressed file — server logs are not paginated
        // across multiple files the way access logs are.
        for (File f : candidates) {
            if (!f.getName().endsWith(".gz")) {
                return readServer(f.getPath(), maxLines);
            }
        }
        return Collections.emptyList();
    }

    // ────────────────────────────────────────────────────────────────────────
    // Tomcat native access log
    //
    // Pattern (application.properties):
    //   %{timestamp}t %m "%U%q" %H %s %b %D %F "%a" "%{X-Forwarded-For}i" "%{X-Real-IP}i"
    //   %v:%p %I %X "%{Host}i" "%{Content-Type}i" %{Content-Length}i
    //   "%{Accept}i" "%{Accept-Encoding}i" "%{Accept-Language}i"
    //   "%{Connection}i" "%{Cache-Control}i" "%{X-Request-Id}i"
    //   "%{Referer}i" "%{User-Agent}i"
    //   "%{Content-Type}o" %{Content-Length}o "%{Content-Encoding}o"
    //   "%{Cache-Control}o" "%{X-Request-Id}o"
    //
    // Positional: method=0 uri=1 protocol=2 status=3 respBytes=4
    //             durationMs=5 ttfbMs=6 remoteIp=7 xForwardedFor=8
    //             referer=22 ua=23 requestId=28 user=29
    // ────────────────────────────────────────────────────────────────────────

    private List<LogEntry> readTomcatAccess(int maxLines) {
        File dir = new File(logDir);
        if (!dir.exists() || !dir.isDirectory()) {
            log.warn("Tomcat log directory not found: {}", logDir);
            return Collections.emptyList();
        }
        // Accept both plain .log (active / not yet compressed) and .log.gz (compressed by
        // AccessLogCompressionTask). Sort newest first — dates in names make lex order correct.
        File[] files = dir.listFiles(f -> {
            String n = f.getName();
            return n.startsWith("access_log.") && (n.endsWith(".log") || n.endsWith(".log.gz"));
        });
        if (files == null || files.length == 0) return Collections.emptyList();
        Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));

        List<LogEntry> result = new ArrayList<>();
        for (File f : files) {
            if (result.size() >= maxLines) break;
            // Re-check existence: AccessLogCompressionTask at 00:10 renames/deletes
            // plain .log files. A file that was listed a moment ago may be gone.
            if (!f.exists()) {
                log.debug("readTomcatAccess: file disappeared (likely just compressed), skipping: {}", f.getName());
                continue;
            }
            int need = maxLines - result.size();
            List<String> lines = f.getName().endsWith(".gz")
                    ? tailLinesGzip(f, need)
                    : tailLines(f, need);
            for (String line : lines) {
                LogEntry e = parseTomcatAccess(line);
                if (e != null) result.add(e);
            }
        }
        return result;
    }

    /**
     * Reads up to {@code maxLines} lines from a gzip-compressed log file, returned
     * newest-first (consistent with {@link #tailLines}).
     *
     * <p>Unlike {@code tailLines}, this reads the entire file into memory (gzip requires
     * sequential access). A size guard prevents decompressing runaway files — at typical
     * access-log compression ratios (~95 %), a 50 MB compressed file can expand to ~1 GB.
     */
    private List<String> tailLinesGzip(File file, int maxLines) {
        final long MAX_COMPRESSED_BYTES = 50_000_000L; // 50 MB compressed → ~1 GB uncompressed
        if (file.length() > MAX_COMPRESSED_BYTES) {
            log.warn("tailLinesGzip: skipping oversized compressed file ({} bytes): {}",
                    file.length(), file.getPath());
            return Collections.emptyList();
        }
        List<String> all = new ArrayList<>();
        try (GZIPInputStream gis = new GZIPInputStream(new FileInputStream(file));
             BufferedReader   br  = new BufferedReader(new InputStreamReader(gis, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.isBlank()) all.add(line);
            }
        } catch (IOException e) {
            log.warn("Failed to read gzip log file: {}", file.getPath(), e);
            return Collections.emptyList();
        }
        // Reverse so newest lines come first, then cap to requested count.
        Collections.reverse(all);
        return all.size() <= maxLines ? all : all.subList(0, maxLines);
    }

    private static final Pattern TOMCAT_ACCESS_TS =
            Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}\\s\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+");

    /** Returns token at index i, or null if absent or equal to "-". */
    private static String tget(List<String> list, int i) {
        if (i >= list.size()) return null;
        String v = list.get(i);
        return (v.isEmpty() || "-".equals(v)) ? null : v;
    }

    private LogEntry parseTomcatAccess(String line) {
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
            String requestSize     = tget(tok, 4);
            long   durMs           = parseLongSafe(tok.get(5)) / 1000; // %D in µs (Tomcat 11+); convert to ms
            String ttfbStr         = tget(tok, 6);
            Long   ttfbMs          = ttfbStr != null ? parseLongSafe(ttfbStr) / 1000 : null; // %F in µs; convert to ms
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
            String user            = tget(tok, 29);
            String auth            = tget(tok, 30);
            String deny            = tget(tok, 31);

            String ip = xForwardedFor != null ? xForwardedFor.split(",")[0].trim()
                      : xRealIp      != null ? xRealIp
                      : remoteIp     != null ? remoteIp : "-";

            return new LogEntry(ts,
                    method, uri, status, durMs,
                    protocol, requestSize, ip,
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
                    user, auth, deny);
        } catch (Exception e) {
            log.warn("parseTomcatAccess: failed to parse line: {}", line, e);
            return null;
        }
    }

    /**
     * Split a log line into tokens, honouring "double-quoted" groups.
     * Handles {@code \"} escape sequences inside quoted tokens so that a
     * User-Agent or URI containing a literal quote doesn't shift all
     * subsequent token indices.
     */
    List<String> tokenize(String s) {
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

    // ────────────────────────────────────────────────────────────────────────
    // Tomcat catalina / error — plain server-log lines (logback pattern)
    //
    //   %d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{50} - %msg%n
    //
    // Stack-trace continuation lines (start with tab/space or 'Caused by:') are
    // appended to the previous entry's throwable.
    // ────────────────────────────────────────────────────────────────────────

    //noinspection RegExpRedundantEscape  -- \\] inside [^...] is redundant but kept for clarity
    private static final Pattern SERVER_LINE = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2}\\s\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+" +
            "([A-Z]+)\\s+" +
            "\\[([^\\]]+)\\]\\s+" +
            "(\\S+)\\s+-\\s+" +
            "(.*)$"
    );

    private List<LogEntry> readServer(String path, int maxLines) {
        List<String> raw = tailLines(new File(path), maxLines);
        // tailLines returns newest-first; reverse to chronological so we can
        // attach stack-trace lines to the entry above them, then reverse again.
        Collections.reverse(raw);
        List<LogEntry> out = new ArrayList<>();
        StringBuilder stack = null;
        LogEntry pending = null;
        for (String line : raw) {
            Matcher m = SERVER_LINE.matcher(line);
            if (m.find()) {
                flushPending(pending, stack, out);
                pending = new LogEntry(reformatTs(m.group(1)),
                        null, null, null, null,
                        null, null, null, null, null,
                        null, null,
                        m.group(2), m.group(3), m.group(4), m.group(5),
                        null, null, null, null,
                        null, null, null, null, null,
                        null, null, null, null, null,
                        null, null, null, null,
                        null, null, null);
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

    private void flushPending(LogEntry e, StringBuilder stack, List<LogEntry> out) {
        if (e == null) return;
        if (stack != null && !stack.isEmpty()) {
            out.add(new LogEntry(e.timestamp(),
                    null, null, null, null, null, null, null, null, null,
                    null, stack.toString(),
                    e.level(), e.thread(), e.logger(), e.message(),
                    null, null, null, null,
                    null, null, null, null, null,
                    null, null, null, null, null,
                    null, null, null, null,
                    null, null, null));
        } else {
            out.add(e);
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Shared file tail — reads backwards in 8 KB chunks
    // ────────────────────────────────────────────────────────────────────────

    private List<String> tailLines(File file, int maxLines) {
        if (file == null || !file.exists() || !file.canRead()) {
            log.warn("Log file not found or not readable: {}", file == null ? null : file.getPath());
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        ByteArrayOutputStream lineBytes = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long pos = raf.length();
            if (pos == 0) return result;

            while (pos > 0 && result.size() < maxLines) {
                int chunk = (int) Math.min(buf.length, pos);
                pos -= chunk;
                raf.seek(pos);
                raf.readFully(buf, 0, chunk);

                for (int i = chunk - 1; i >= 0; i--) {
                    byte b = buf[i];
                    if (b == '\n') {
                        if (lineBytes.size() > 0) {
                            result.add(decodeReversed(lineBytes));
                            lineBytes.reset();
                            if (result.size() >= maxLines) break;
                        }
                    } else {
                        lineBytes.write(b);
                    }
                }
            }
            if (lineBytes.size() > 0 && result.size() < maxLines) {
                result.add(decodeReversed(lineBytes));
            }
        } catch (IOException e) {
            // Can happen when AccessLogCompressionTask deletes a .log file mid-read.
            log.warn("Failed to tail log file (may have been rotated mid-read): {}", file.getPath(), e);
        }
        return result;
    }

    private String decodeReversed(ByteArrayOutputStream baos) {
        byte[] bytes = baos.toByteArray();
        for (int i = 0, j = bytes.length - 1; i < j; i++, j--) {
            byte tmp = bytes[i]; bytes[i] = bytes[j]; bytes[j] = tmp;
        }
        int len = bytes.length;
        if (len > 0 && bytes[len - 1] == '\r') len--;
        return new String(bytes, 0, len, StandardCharsets.UTF_8);
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

    // ────────────────────────────────────────────────────────────────────────
    // Exclusions log — one JSON object per line written by AccessLogExclusionFilter
    //
    //   {"ts":"yyyy-MM-dd HH:mm:ss.SSS","method":"GET","uri":"/...","status":200,"durationMs":5,"ip":"1.2.3.4"}
    // ────────────────────────────────────────────────────────────────────────

    private List<LogEntry> readExclusions(int maxLines) {
        File dir = new File(logDir);
        if (!dir.exists() || !dir.isDirectory()) return Collections.emptyList();

        // findLogFiles handles both the dated format (exclusions.YYYY-MM-DD.N.log — used
        // when Logback's <file> option is omitted) and the legacy fixed-name format
        // (exclusions.log — used when <file> is set). Both exist in sorted newest-first order.
        File[] filesArr = findLogFiles(dir, "exclusions");
        List<File> files = Arrays.asList(filesArr);

        List<LogEntry> result = new ArrayList<>();
        for (File f : files) {
            if (result.size() >= maxLines) break;
            List<String> lines = f.getName().endsWith(".gz")
                    ? tailLinesGzip(f, maxLines - result.size())
                    : tailLines(f, maxLines - result.size());
            for (String line : lines) {
                LogEntry e = parseExclusionLine(line);
                if (e != null) result.add(e);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private LogEntry parseExclusionLine(String line) {
        if (line == null || line.isBlank()) return null;
        try {
            Map<String, Object> m = OBJECT_MAPPER.readValue(line, Map.class);
            String  ts         = reformatTs((String) m.get("ts"));
            String  method     = (String)   m.getOrDefault("method",     "-");
            String  uri        = (String)   m.getOrDefault("uri",        "-");
            int     status     = ((Number)  m.getOrDefault("status",     0)).intValue();
            long    durationMs = ((Number)  m.getOrDefault("durationMs", 0L)).longValue();
            String  ip         = (String)   m.getOrDefault("ip",         "-");
            String  requestId  = (String)   m.getOrDefault("requestId",  null);
            String  user       = (String)   m.getOrDefault("user",       null);
            String  auth       = (String)   m.getOrDefault("auth",       null);
            String  deny       = (String)   m.getOrDefault("deny",       null);
            return new LogEntry(ts,
                    method, uri, status, durationMs,
                    null, null, ip, null, null, requestId, null,
                    null, null, null, null,
                    null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null,
                    null, null, null, null,
                    user, auth, deny);
        } catch (Exception e) {
            return null;
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Bodies log lookup (one entry by requestId)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Scans {@code bodies.*.log}/{@code .log.gz} files newest-first and returns
     * the first JSON line that contains {@code "requestId":"<requestId>"}, or
     * {@code null} if no entry matches.
     *
     * <p>Substring match is sufficient because {@link com.eventhorizon.weblog.filter.BodyCaptureFilter}
     * always emits the field in that exact form (no whitespace, key first) and the
     * request ID character set is restricted by {@link com.eventhorizon.weblog.filter.RequestIdFilter#SAFE_ID}
     * to alphanumerics + {@code -_}, so collisions with body content are
     * effectively impossible.
     */
    private String findBodyEntry(String requestId) {
        if (requestId == null || requestId.isBlank()) return null;
        File dir = new File(logDir);
        if (!dir.exists() || !dir.isDirectory()) return null;
        File[] files = findLogFiles(dir, "bodies");
        if (files.length == 0) return null;
        String needle = "\"requestId\":\"" + requestId + "\"";
        for (File f : files) {
            List<String> lines = f.getName().endsWith(".gz")
                    ? tailLinesGzip(f, 50_000)
                    : tailLines(f, 50_000);
            for (String line : lines) {
                if (line.contains(needle)) return line;
            }
        }
        return null;
    }

    /** Converts "yyyy-MM-dd HH:mm:ss.SSS" → "dd-MMM-yyyy HH:mm:ss.SSS". */
    private static String reformatTs(String ts) {
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
