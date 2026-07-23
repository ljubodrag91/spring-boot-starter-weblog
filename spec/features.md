# Features

## RequestIdFilter

Runs at `Ordered.HIGHEST_PRECEDENCE`. On every request:

1. Reads the inbound `X-Request-Id` header. Accepts it only if non-blank, ≤64 characters, and
   matches `[a-zA-Z0-9\-_]+` — otherwise generates a UUID. This range rejects anything that
   could cause log injection or break JSON encoding (whitespace, quotes, braces).
2. Sets `X-Request-Id` on the response — captured by the Tomcat access log at the fixed
   pattern's token[28] (`"%{X-Request-Id}o"`).
3. Puts the same value in SLF4J MDC under `requestId`. A consuming app's own Logback pattern
   can reference it via `%X{requestId:-}`.
4. Removes the MDC key in a `finally` block to avoid leaking across thread-pool reuse.

A client that supplies its own `X-Request-Id` gets that same ID echoed back and correlated
across every log file (access, catalina, error, exclusions) — useful for tracing one request
end-to-end across a call chain.

## AccessLogExclusionFilter

Runs at `HIGHEST_PRECEDENCE + 1` — after `RequestIdFilter`, so the response's `X-Request-Id`
header is already set when this filter's `finally` block reads it back. It matches a request's
path (context path stripped) against two prefix tiers:

**Excluded** (`log-viewer.excluded-prefixes`, default `/admin/logs`, `/swagger-ui`, `/v3/api-docs`, `/actuator`):

1. Sets the `skipLog` request attribute, which Tomcat's `AccessLogValve` checks via
   `condition-unless=skipLog` — the request never reaches the main access log.
2. Writes a compact JSON line (timestamp, method, URI, status, duration, IP, request ID) to
   the `com.eventhorizon.weblog.exclusions` logger, which the included Logback config routes to the
   exclusions log under `log-viewer.access-log-directory` (dated file, e.g. `exclusions.<date>.0.log`).

This keeps tooling/health-check noise out of the primary access log while still recording it
in an audit trail.

**Silent** (`log-viewer.silent-prefixes`, default the viewer's own self-traffic:
`/admin/logs/inflight`, `/admin/logs/data`, `/admin/logs/page`, `/admin/logs/trace`,
`/admin/logs/log-viewer.`): sets `skipLog` (suppressed from the access log) but writes **no**
`exclusions.log` line — recorded nowhere. A silent match is checked first and alone suppresses the
access log, so a path matching both tiers (e.g. `/admin/logs/data`, which is also under the
`/admin/logs` excluded prefix) is silenced rather than logged. This exists because the live
in-flight strip polls `GET /admin/logs/inflight` every `inflight-refresh` (default 3s) whenever the
viewer is open; without silencing, each tick would append an `exclusions.log` line and bury the
genuine excluded traffic. The bare `/admin/logs` page load is *not* silent — an admin opening the
viewer stays in the audit trail. Operators can add their own noise (e.g. `/actuator/health` probes).

## LogViewerController

Serves `/admin/logs` (HTML shell) and `/admin/logs/data` (JSON, what the frontend actually
fetches). The shell's CSS and JS are separate files (`log-viewer.css` / `log-viewer.js`) served as
static, ETag-revalidated assets at `/admin/logs/log-viewer.css` and `/admin/logs/log-viewer.js`
(under the same `/admin/logs/**` auth); only the HTML carries the server-side `[[..]]` placeholders,
and it hands per-request bootstrap values to the script via an inline `window.__INIT` object.

Reads four log types from disk on every request — nothing is cached in memory:

- **access** — Tomcat's native access log (`access_log.*.log` / `.log.gz`), parsed by fixed
  token position per the 31-token base pattern (plus an optional trailing request-attribute
  token) `WebLogAutoConfiguration` registers.
- **exclusions** — the JSON lines `AccessLogExclusionFilter` writes.
- **catalina** / **error** — Logback-managed server logs, parsed by regex
  (`SERVER_LINE` pattern), with stack-trace continuation lines folded into the entry above them.

All four types share one newest-first read path (`readByPrefix`): files are discovered via
`findLogFiles` (plain `.log` + compressed `.log.gz`) and read most-recent-first until the requested
line count is reached. Plain files are read backwards in 8 KB chunks (`tailLines`); compressed files
are streamed through a bounded sliding window (`tailLinesGzip`). So a "last N lines" request never
loads an entire multi-hundred-MB file into memory — heap stays bounded to N entries regardless of a
file's compressed or decompressed size, and only one file is open at a time. Compressed files are
size-guarded at 50 MB compressed (`max-compressed-read-bytes`) — a **CPU/latency** guard (gzip can't
seek, so reading a `.gz`'s tail must inflate the whole file), not a memory guard; oversized archives
are skipped on the default tail view. (Catalina/error `.gz` archives are read too, as of 1.2.0 —
before that the server-log tabs ignored every compressed file and showed only the newest plain one.)

### Paged / date-ranged history (`GET /admin/logs/page`)

`/data` is a live tail (newest N). To browse *back* into history there is `/admin/logs/page`, which
returns one bounded page positioned by a cursor and direction, optionally within a `from`/`to` day
window:

- **File selection by filename date.** `from`/`to` (yyyy-MM-dd) pick only the files whose embedded
  date falls in the window — a month-wide range is a *filter*, not a bulk open. Files are then read
  lazily, newest-first, one at a time, and reading stops the instant the page is filled. So a
  month-wide window and a single-day window cost the same memory; only the eligible file set differs.
- **Cursor = `{ file, index }`** — the boundary entry's file and its per-file chronological index
  (0 = oldest entry in that file), stable because archives are write-once. `dir=older`/`newer` pages
  in either direction; `LogPage` returns `oldest`/`newest` cursors plus `hasOlder`/`hasNewer`. The
  client echoes a cursor back to page further; the server always resolves its `file` against the
  discovered file set (`indexOfFile`), never as a path, so there is no traversal surface.
- **Live, bounded gzip reads.** Paged reads use forward streaming (`streamEntries` → `streamLines`)
  with a page-sized sliding window, so heap stays bounded to the page regardless of file size/count.
  Because the read is explicit, it is **not** subject to the 50 MB tail-view skip — a large archive
  is read (you wait; memory stays flat) rather than silently dropped. Page size is capped at 20 000
  (below the tail's 50 000), since a paged read may inflate whole archives.
- **Cost to know:** gzip can't seek, so each page inflates its file(s) from the top up to the cursor
  — deep paging into one huge day re-inflates that day per page (latency, not memory).

`/admin/logs/trace` (the 5xx→stack-trace join) takes an optional `date`; when present it scopes the
search to that day's `error` file(s) ± one day and reads `.gz`, so a trace resolves even after the
error log has rotated and compressed.

### Logging a configurable request attribute

`X-RateLimit-Limit`/`X-RateLimit-Remaining` are captured as ordinary response-header tokens
(`%{name}o`) — nothing sensitive about them. For an app-supplied per-request value (e.g. an
API key), the pattern appends one trailing `%{name}r` token, where `name` is
`log-viewer.request-attribute` (default `apiKey`; empty disables the token). An `r`-suffixed
token reads a **request attribute**, not a header, so the consuming app fully controls what
it puts there via `request.setAttribute("apiKey", value)`; the parser reads it into
`LogEntry.apiKey`. The value is logged **verbatim** — the starter does not mask it; an app
that needs a masked form must mask before setting the attribute. Prefer this over a
header-based token (`%{name}i`), which would capture the raw header value with no chance to
transform it at the app layer.

### Client IP resolution

Each parsed access-log entry needs one `ip` value, but the log line carries three candidates:
the connection's direct peer (`%a`, token[7]), `X-Forwarded-For` (token[8]), and `X-Real-IP`
(token[9]). Which one is trustworthy depends on whether the consuming application has told its
embedded Tomcat to resolve forwarded headers itself (`server.forward-headers-strategy=native`,
Tomcat's `RemoteIpValve`) — if it has, `%a` is already the validated, non-spoofable client IP;
if it hasn't, `%a` is just whatever directly connected (possibly a proxy), and the header
values are the more useful signal despite being unvalidated.

`LogViewerController` mirrors that same property (`server.forward-headers-strategy`) to decide:

- **Set to anything other than `none`** (or Spring Boot's own default when unset): prefer `%a`.
- **Unset / `none`**: fall back to `X-Forwarded-For` (first value in a comma-separated chain,
  trimmed) → `X-Real-IP` → `%a`, in that order — unchanged from the starter's original
  behavior.

This means the log viewer becomes more correct automatically the moment a consuming app
enables forwarded-header resolution, with zero configuration on the log-viewer side and no
behavior change for apps that haven't.

### Detail-view tooltips and section coloring

The row/detail modal (opened from a table row) groups fields into sections — Core facts,
Network, Request, Response — each with its own accent color. Every field's key label carries
a `border-left` in its section's color (amber for Core, blue for Network, plum for Request,
green for Response) and a `title` tooltip explaining what the field means, so hovering any
value pair surfaces its definition without needing to consult docs. This applies to both the
access-log and server-log (catalina/error) detail views.

## AccessLogCompressionTask

`@Scheduled(cron = "0 10 0 * * *")` — 00:10 daily, ten minutes after the midnight rotation,
giving the OS time to release the file handle on the just-rotated file (relevant on Windows,
which holds handles briefly after `close()`). Scans `log-viewer.access-log-directory` and, for
**every prefix** in `log-viewer.compression-log-prefixes` (default `access_log, catalina,
error, exclusions`), gzips each past-day `<prefix>.<date>[.<i>].log` to `.log.gz` and deletes
the original. "Past-day" is decided by parsing the date immediately after the prefix and
keeping only dates strictly before today — this leaves today's active file alone and skips
undated legacy names (e.g. Tomcat's own `catalina.log`). If a `.gz` already exists for a
candidate (a previous run compressed it but couldn't delete the source), the plain file is
deleted without re-compressing.

Both the Tomcat access log and the Logback catalina/error/exclusions logs are handled the same
way on purpose: the starter's Logback appenders roll to **plain `.log`** (no `.gz`, no
`maxHistory`/`totalSizeCap`) so this task is the single owner of their compression. This matters
because Logback only compresses at a rollover that happens while the JVM is running — on an app
that is down over midnight that step is silently skipped and files orphan as plain `.log`
forever. Because the task re-scans the directory on every run, a single run catches up the
**entire backlog** accumulated on nights the app was down, not just the previous day.

**No retention.** The task compresses only — it never deletes `.log.gz` archives, and Tomcat's
own access-log `maxDays` cleanup is disabled (`-1`). Archives are kept indefinitely; bounding disk
usage is left to the operator (external rotation, disk monitoring, or manual cleanup).

`@EnableScheduling` is declared on `WebLogAutoConfiguration` itself, so this task fires without
the consuming app needing to add the annotation anywhere.
