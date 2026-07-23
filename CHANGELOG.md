# Changelog

All notable changes to `spring-boot-starter-weblog` are documented here.
This project adheres to [Semantic Versioning](https://semver.org/).

## [1.6.0-sb3] — 2026-07-24

Consolidates the starter into **one tree targeting Spring Boot 3**. The repository previously
held **two divergent source trees** — the tracked, released line (1.3.1-sb3, Spring Boot 3) and
a second, never-committed tree (1.5.0, Spring Boot 4) under a different package root — neither a
superset of the other. There is now one tree, under `com.eventhorizon`, carrying both feature
sets and building against Boot 3.

### Changed
- **Package and groupId are `com.eventhorizon` throughout.** The foreign package root, and the
  internal Nexus host that came with it, are gone. The artifactId is unchanged.
- **Target platform is Spring Boot 3.2.4 / Java 21**, so the consuming application does not have
  to move to Boot 4. The newer tree this is built from targeted Boot 4.0.5 / Java 25; the
  backport turned out to need only two things, because nothing in the code uses a Java feature
  past 21 and 39 of its 40 Spring imports are identical across Boot 3 and 4:
  - `TomcatServletWebServerFactory` imported from `org.springframework.boot.web.embedded.tomcat`
    rather than Boot 4's `org.springframework.boot.tomcat.servlet` (one import, one file);
  - `spring-boot-starter-web` instead of `spring-boot-starter-webmvc` + the Boot-4-only
    `spring-boot-tomcat` optional dependency, since Boot 3's starter still brings Tomcat
    transitively.

  Annotation-processor versions are pinned explicitly again on `annotationProcessorPaths`:
  maven-compiler-plugin 3.11.0 (managed by Boot 3.2.4) does not inherit dependency management
  there, so unversioned paths fail the build. Retains the `-sb3` suffix, matching the existing
  tag line, since the version number alone no longer implies the Boot generation.
- **Publishing moved to GitHub Packages**
  (`https://maven.pkg.github.com/ljubodrag91/spring-boot-starter-weblog`), matching
  `.github/workflows/publish.yml` and how the consuming app already resolves the artifact.
  `distributionManagement` previously pointed at a Nexus host the CI workflow never used.
  The publish workflow's JDK moved 21 → 25 to match `java.version`.
- **The access-log pattern grew from 31 to 34 base tokens.** Tokens 31/32/33 are the `user`,
  `auth` and `deny` request attributes reinstated from the fork; the optional
  `%{<name>}r` request-attribute token moves from index 31 to 34. **The viewer parses by fixed
  index, so access-log files written by an earlier version parse their first 31 tokens
  correctly but yield no user/auth/deny.** Nothing is mis-indexed — the new fields simply read
  as absent on old lines.

### Added (reinstated from the `com.eventhorizon` fork)
- **`AuthInfoFilter` — Authorization summary + deny reason for 401/403 debugging.** Records a
  sanitized summary of the `Authorization` header (scheme; for a JWT its `sub`/`type`/`exp` plus
  a human "expired 18m ago") into a request attribute the access-log valve captures. The raw
  credential is never logged and the summary is restricted to quote-free printable ASCII, so it
  cannot shift the positional parser. Consumer apps set `AuthInfoFilter.DENY_REQ_ATTR` at their
  rejection point to record *why* a request was denied.
- **Authenticated-user capture.** A `LOWEST_PRECEDENCE` companion filter records the Spring
  Security principal (looked up reflectively, so the starter still works with Security absent)
  into the access log, `exclusions.log`, and a new sortable **User** column in the viewer with
  stable per-user row tinting.
- **`BodyCaptureFilter` — opt-in request/response body capture** to `bodies.log`
  (`log-viewer.body.enabled`, default `false`), with an 8 KB per-side cap, binary/multipart
  skipping, and flat-JSON key redaction. `bodies` joins the nightly compression sweep and gets a
  `WEBLOG_ASYNC_BODIES` appender in the Logback include.
- The viewer's detail panel gained **Authenticated user**, **Authorization** (flagged when the
  token is expired, or called out explicitly when a 401/403 carried no header at all) and
  **Denied because** rows; free-text search now matches the user.

### Fixed
- **`/actuator` restored to the `excluded-prefixes` default.** It was added to the released line in
  1.1.0-sb3 but absent from the newer tree this merge is built on, so consolidating would have
  silently started writing every Actuator/health-probe request into the access log. Consumers
  relying on the default (rather than overriding the property) get the previous behaviour back.
- **Sorting by a nullable column threw a `TypeError`.** `doSort` tested only the left operand
  for `typeof === 'string'` before calling `.toLowerCase()` on both, so any column with absent
  values crashed the sort. Latent until now — `user` is the first sortable field that is
  genuinely null on most rows. Both operands are now coerced, with missing values sorting
  together as `''`.
- **`exclusions.log` keeps the Jackson serialization** introduced in 1.0.6 rather than the
  fork's string concatenation, so the reinstated `user`/`auth`/`deny` fields cannot be used for
  JSON log-injection.

## [1.5.0] — 2026-07-23

Hardening pass from a full engineering audit. Several defaults changed — review the
**Changed** section before upgrading.

### Security
- **Viewer is no longer silently unauthenticated.** When the viewer is enabled but Spring Security
  is not on the classpath (so `WebLogSecurityAutoConfiguration` cannot activate), the app now logs a
  prominent startup `WARN` that `/admin/logs/**` is exposed with no auth. (S-1)
- **`log-viewer.enabled`** (default `true`) — master switch. When `false` the `LogViewerController`
  and all `/admin/logs/**` endpoints are not registered, while request-id/exclusions/compression/slow
  watchdog keep working. Use it wherever the viewer cannot be authenticated. (S-1)
- **`log-viewer.required-authority`** — the fallback security chain can now demand a specific
  authority (e.g. `ROLE_ADMIN`) instead of merely "authenticated". (S-5)
- **Query strings are no longer written to `slow.log`.** The in-flight record now stores the request
  path only; query strings routinely carry tokens/keys. (S-3)
- **`log-viewer.request-attribute` is validated** before being interpolated into the Tomcat access-log
  pattern; a value with pattern-breaking characters (`}`, `"`, spaces) is ignored rather than
  corrupting the pattern and mis-indexing every parsed field. (S-4)

### Changed
- **`log-viewer.request-attribute` now defaults to empty (feature off).** It previously defaulted to
  `apiKey`, which invited logging a secret verbatim to the access log and the viewer. Set
  `log-viewer.request-attribute=apiKey` (or `tenantId`, etc.) to opt back in. (S-2)
- **Removed the server-side `50000` "last N lines" option**, aligning the tail reader's ceiling with
  the UI dropdown (max `20000`). Requests for more snap to `20000`. (L-15)
- `/data` JSON: the access-log response-bytes field was renamed `requestSize` → `responseBytes`
  (it always held `%b`, the response body size, never the request size). (C-2)

### Fixed
- **Configurable access-log time unit (`log-viewer.access-log-time-unit`, default `MICROS`).** The
  parser divides `%D`/`%F` by 1000 assuming microseconds; Tomcat documents these as milliseconds.
  If your durations/p95 look 1000× too small, set this to `MILLIS`. **Verify against your Tomcat.** (C-1)
- **Potential `NoClassDefFoundError` on non-Tomcat servlet stacks.** The Tomcat access-log customizer
  moved into a nested `@ConditionalOnClass(TomcatServletWebServerFactory)` configuration, so the outer
  auto-configuration no longer names Tomcat types in its bean signatures. (R-1)
- **Quadratic tail backfill.** `newestEntries` now grows its read window geometrically instead of by
  the exact shortfall, so a file dominated by droppable lines no longer triggers O(passes × filesize)
  re-reads (and repeated full `.gz` decompression). (P-1)
- **ETag collisions.** Static-asset ETags now derive from SHA-256 rather than `String.hashCode()`,
  so a content change can no longer collide and serve a stale asset. (R-4)
- **Browser memory leak on long live-tail.** The client's `flashedKeys` set is now bounded to the rows
  still held, instead of growing for every row ever seen. (P-2)

### Reliability
- **Audit/slow streams no longer drop lines under burst.** The `exclusions` and `slow` async appenders
  switched to `neverBlock=false` with a larger queue, so sustained overload applies brief back-pressure
  rather than silently discarding audit entries and slow-request evidence. (R-2)

### Documentation
- Documented that `error.log` only captures `org.apache.*` ERRORs, so the 5xx→trace join misses
  exceptions logged under the app's own loggers — with a copy-paste snippet to route them to the error
  appender. (R-3)

### Refactoring
- Extracted the duplicated request-time IP resolution into a shared `ClientIp` helper, and the rolled-
  log filename/date parsing into a shared `LogFileNames` helper. (A-3, A-4)
- **Decomposed the 1,300-line `LogViewerController`** into three focused classes: `LogFileReader`
  (file discovery, backward tailing, gzip streaming — parser-agnostic), `LogParser` (all four
  text→`LogEntry` formats + stack-trace folding), and the controller itself (routing, records, paging
  orchestration). Behaviour unchanged; all covered by the existing suite. (A-1)
- **Split the 1,908-line `log-viewer.js`** into four concern files (`core`, `render`, `modal`, `ui`)
  that the controller concatenates, in order, into one served script. The served bytes are identical
  to the former monolith (verified), and the `/admin/logs/log-viewer.js` URL is unchanged. (A-2)

### Testing
- Added tests for `InFlightRegistry`, `SlowRequestWatchdog` (threshold + one-shot latch + disabled),
  `InFlightRequestFilter` (tracking lifecycle, `skipLog` bypass, drain-on-throw), the `/admin/logs/inflight`
  endpoint, the `access-log-time-unit=MILLIS` path, and `accessLogPattern` attribute validation.
  Suite: 129 → 147 tests.

## [1.4.3] — 2026-07-22

### Fixed
- **Live in-flight strip flooded `exclusions.log` with its own poll.** The strip polls
  `GET /admin/logs/inflight` every `inflight-refresh` (default 3s) whenever the viewer is open on the
  access tab. That path sits under the `/admin/logs` excluded prefix, so `AccessLogExclusionFilter`
  wrote one `exclusions.log` line per tick (~20/min per open viewer, indefinitely), drowning the
  genuine excluded traffic (Swagger, `/v3/api-docs`, the `/admin/logs` page load) the exclusions audit
  exists to record. The `inflight()` javadoc even claimed "no feedback loop" — true for the access log
  and the in-flight registry, but it overlooked `exclusions.log` as a third sink. Introduced in 1.4.0.

### Added
- **`log-viewer.silent-prefixes`** — a quieter tier below `excluded-prefixes`. A silent path is
  suppressed from the Tomcat access log **and** `exclusions.log` (recorded nowhere), whereas an
  excluded path stays audited in `exclusions.log`. Silent is checked first, so a path matching both
  tiers is silenced rather than logged. Default silences the viewer's own self-traffic
  (`/admin/logs/inflight`, `/admin/logs/data`, `/admin/logs/page`, `/admin/logs/trace`,
  `/admin/logs/log-viewer.`) while leaving the bare `/admin/logs` page load — a real "an admin opened
  the viewer" event — in the audit trail. Operators can add their own noise (e.g. `/actuator/health`
  liveness probes). This is the fix vehicle for the flood above.

### Testing
- Added `AccessLogExclusionFilterTest` cases proving silent paths set `skipLog` yet write no
  `exclusions.log` line, and that excluded-but-not-silent paths (incl. the bare `/admin/logs` page)
  still get their audit line.

## [1.4.2] — 2026-07-22

### Fixed
- **Log viewer: tail view returned N−1 rows when the window held an unparseable line.** Selecting
  "last 5 000" could render 4 999 (10 000 → 9 999, etc.). `readByPrefix` read exactly `N` *raw* lines
  and then dropped any the parser rejected — a blank/garbage/foreign-format line, or (for the server
  logs) a folded stack-trace continuation line — without reading further back to make up for them, so
  the response was short by one per dropped line in the window. It now backfills: it keeps reading
  older lines until it has `N` *parsed* entries or the file is exhausted. The common zero-drop case
  still does a single read (no cost change); plain files keep their backward-seek tail and `.gz` files
  stay behind the compressed-size guard. As a side effect, tailing the server logs now yields `N`
  entries rather than `N` lines (multi-line stack traces no longer eat into the count).
- **Log viewer: live in-flight strip vanished on a transient poll failure.** A non-OK response to
  `GET /admin/logs/inflight` (e.g. a session/auth blip, or a stumble under heavy load) was funnelled to
  the "hide the whole strip" path, so the panel disappeared mid-tail instead of holding its last state.
  A non-OK status is now treated like a network error — the last render is kept and the next tick
  retries. The strip is hidden only when the view is genuinely inactive (wrong tab, range mode, or a
  hidden document).

### Testing
- Added a regression test proving the tail view returns the full requested count with an unparseable
  line inside the window (fails at `1999` before the fix, passes at `2000` after).
- Fixed a pre-existing gap in `LogViewerControllerTest` where the manually-constructed controller was
  missing its `webLogProperties` / `inFlightRegistry` collaborators, so the HTML `view()` endpoint
  tests NPE'd; both are now injected in setup.

## [1.4.1] — 2026-07-22

### Fixed
- **Log viewer: in-flight strip table readability.** The live in-flight rows were hard to read:
  the flexible `1fr` column sat in the middle (URI), blowing out into a wide empty gap between the
  URI and the trailing columns on desktop; the last column (`thread`) was too narrow and clipped by
  the strip's `overflow: hidden`; and the table had no column headers. Reordered the shared grid track
  to `age · started · ip · thread · method · uri` with the flexible column **last** (fixed columns now
  hug the left, no mid-row gap; long URIs truncate cleanly at the right edge), widened `thread`, and
  added a faint header row that aligns to the same track and hides with the rows when collapsed.

## [1.4.0] — 2026-07-22

### Added
- **Slow / never-completing request watchdog — logs requests the access log can't.** The Tomcat
  access log writes a line only when a request *completes*, so a request that is still running, one
  that never completes (hung worker, JVM death), or one that finishes only after the client already
  abandoned it (logged as a misleading `connFate +` when the compressed response fit the socket
  buffer before the abort was noticed) leaves no usable trace. A new highest-precedence
  `InFlightRequestFilter` records each request in an `InFlightRegistry` on entry and removes it on
  completion; a scheduled `SlowRequestWatchdog` sweeps that registry and writes one compact-JSON
  `SLOW` line to a new **`slow.log`** for any request in flight past a threshold.
  - **Purely observational** — nothing is ever cancelled, interrupted, or timed out; the threshold
    is a *reporting* age, not a deadline. Each request is reported at most once.
  - Reading model: a `SLOW` line **with** a matching access-log line (same `requestId`) = slow but
    finished; a `SLOW` line **with no** matching access-log line = never finished (hung/killed).
  - Config under `log-viewer.*`: `slow-request-logging-enabled` (default `true`),
    `slow-request-threshold` (default `30s`), `slow-request-sweep` (default `5s`).
  - Each `SLOW` line carries the request-side context available at entry — `method`, `uri`, `protocol`,
    `host`, resolved `ip`, `xForwardedFor`, `userAgent`, `referer`, `thread` — mapped to the same
    `LogEntry` fields the access log uses, so the Slow tab isn't sparse. Response-side fields
    (status/bytes/timing/`connFate`/response headers) stay null: an in-flight request has none yet.
  - `slow.log` joins the `AccessLogCompressionTask` sweep (added to `compression-log-prefixes`) and
    gets the same daily gzip + no-retention treatment, plus the viewer's multi-file/gz history + paging.
- **Log viewer: "Slow" tab + live in-flight strip.** The viewer gains a **🐌 Slow** tab that reads
  `slow.log` (JSON lines, mirrors the "Excluded" tab) so slow / never-completed requests are browsable
  after the fact without shelling into the box. New read-only **`GET /admin/logs/inflight`** returns a
  live snapshot of requests running *right now* (registry projection: `requestId`, `method`, `uri`, `ip`,
  `thread`, `startedAt`, server-computed `inFlightMs`, `slow` flag past threshold, plus `count` = registry
  size as a leak signal). A **live strip** atop the access view renders it, collapsing to a thin
  `In-flight: 0` bar when idle, with a **1s countdown to the next refresh** and a **started-time** column.
  Under the same `/admin/logs/**` auth, excluded so the poll neither access-logs nor self-registers.
  New config: `inflight-view-enabled` (default `true`), `inflight-refresh` (default `3s`).
  - Design: `spec/live-inflight-view.md`.

## [1.3.0] — 2026-07-20

### Added
- **Responsive log viewer — usable on phones and tablets.** At ≤720px the filter sidebar collapses
  into an off-canvas drawer opened by a new **☰ Filters** button in the table header (tap the button
  again or the backdrop to close); the drawer scrolls when the facet list is long. The "slow lane"
  leaderboard, previously hidden on narrow screens, is now relocated into the bottom of that drawer so
  it stays reachable. The table switches to a compact column set (row-number and request-id dropped,
  time-of-day only, so the request path keeps the width) and the modal, histogram and masthead reflow.

### Fixed
- **Log-viewer table rendered blank on mobile (≤720px).** With the sidebar turned into a fixed
  off-canvas drawer and the leaderboard hidden, the table was the only in-flow grid item and
  auto-placed into a leftover 0-width column (the `.no-facets` track rule from the ≤1100 breakpoint
  won on specificity over the intended single-column layout). The table now spans the full width at
  mobile widths regardless of the collapsed-panel state.
- **Log-viewer history paging no longer shows a spurious empty page at page boundaries.** The
  `Older`/`Newer` buttons were driven by `hasOlder`/`hasNewer` flags computed as "did this page come
  back full?" (`page.size() == pageSize`). That proxy is wrong when the remaining entry count is an
  exact multiple of the page size: the button stayed enabled on the last real page, so the next click
  fetched an empty page and only then disabled the button — reading as a blank screen or an
  unresponsive button. Both `olderPage`/`newerPage` now use an N+1 look-ahead (fetch one extra entry,
  set the flag from whether it exists, then trim it) so the flags reflect whether a further page
  actually exists. Paging stays contiguous.

## [1.2.0] — 2026-07-20

### Changed
- **`AccessLogCompressionTask` now owns compression for the catalina/error/exclusions logs too,
  not just the access log.** The Logback appenders in `logback-weblog-include.xml` now roll to
  **plain `.log`** (dropped the `.gz` suffix and the `maxHistory`/`totalSizeCap` from their rolling
  policies); the nightly task gzips them via the same directory sweep it already ran for
  `access_log`. Motivation: Logback only compresses at a rollover that occurs while the JVM is
  running, so an app that is down over midnight orphaned catalina/error files as uncompressed
  `.log` indefinitely. The task re-scans on every run, so one run catches up the entire backlog
  regardless of when the app was up — the behavior access logs already had.
- **Retention removed — archives are never deleted.** The compression task no longer prunes by age
  or size (the `compression-retention-days` and `compression-total-size-cap` properties are gone),
  and Tomcat's access-log `maxDays` cleanup is disabled (`-1`). `.log.gz` archives are kept
  indefinitely; bounding disk usage is now the operator's responsibility.
- **Log-viewer assets split into separate files for maintainability.** The CSS and JS are no longer
  inlined in `log-viewer.html` — they live in `log-viewer.css` / `log-viewer.js` and are served as
  static, ETag-revalidated assets at `GET /admin/logs/log-viewer.css` and `…/log-viewer.js` (covered
  by the existing `/admin/logs/**` auth). The HTML keeps the server-side `[[..]]` placeholders and now
  passes per-request bootstrap values to the script via an inline `window.__INIT` object. No behavior
  change for users.

### Added
- **`log-viewer.compression-log-prefixes`** (default `access_log, catalina, error, exclusions`) —
  the set of log-filename prefixes the compression task sweeps. The "past-day" selection now parses
  the date after each prefix (strictly-before-today), which also skips undated legacy names such as
  Tomcat's own `catalina.log`.
- **`GET /admin/logs/page` — paged, date-ranged history browsing.** Unlike `/admin/logs/data`
  (newest-N live tail), this returns one bounded page positioned by a `cursor`+`dir`
  (`older`/`newer`), optionally constrained to a `from`/`to` day window. Files are selected by the
  date in their name and opened lazily, newest-first, one at a time, only until the page is filled —
  so a month-wide window costs the same memory as a single day. `.gz` archives are read live (no
  temp files) and are **not** subject to the `max-compressed-read-bytes` skip that guards the tail
  view. Response is a `LogPage { entries, oldest, newest, hasOlder, hasNewer }` where the cursors
  are `{ file, index }` (per-file chronological index; stable because archives are write-once). Page
  size is capped at **20 000** (below the tail view's 50 000) since a paged read may inflate whole
  archives. The `cursor` file is always resolved against the discovered file set, never as a path.
- **`GET /admin/logs/trace` now accepts an optional `date`** (the clicked row's date). When given,
  the stack-trace lookup is scoped to that day's `error` file(s) ± one day and reads `.gz` archives,
  so a trace resolves even for an error that has already rotated and compressed. Without `date` it
  falls back to scanning the newest `lines` error entries as before.
- **Log-viewer UI: a top-bar "🕘 History" control** drives the paged endpoint. It reveals a
  from/to date window and ◀ Older / Newer ▶ buttons that page through server-selected archives
  (including `.gz`), disabled at the ends via `hasOlder`/`hasNewer`. History mode is mutually
  exclusive with live tail; while active, the sidebar time-range is hidden and a note clarifies that
  search/facets apply within the loaded batch. Row detail's "View stack trace" now passes the row's
  date to `/trace`. The tail-view line dropdown drops the 50 000 option (paged reads cap at 20 000).

### Fixed
- **The log viewer now reads compressed `.gz` archives for catalina/error logs, not just the newest
  plain file.** `readServerByPrefix` previously returned the first non-`.gz` file and ignored every
  archive, so once a server log was compressed its history vanished from the "Server Log"/"Error Log"
  tabs (and from the `/trace` stack-trace join). All four log types (access, catalina, error,
  exclusions) now share one newest-first read path that streams `.gz` through the same bounded
  sliding window, so historical server-log entries — and stack traces for already-compressed
  errors — are visible again. Memory stays bounded to the requested line count regardless of file
  size or count.

## [1.1.3] — 2026-07-17

### Added
- **"499 · Aborted" status facet in the log viewer.** Client-aborted requests (connFate `X`,
  already surfaced inline as nginx-style `200(499)`) can now be isolated with a dedicated facet
  in the Status group. It's a synthetic bucket matched on the abort marker rather than the logged
  status, so it spans all real status codes and toggles like any other status facet. Aborts remain
  searchable only here — free-text search still matches uri/ip/ua/requestId, not the 499 marker.

## [1.1.2] — 2026-07-17

### Fixed
- **Time-range presets now anchor to wall-clock now, not the newest loaded row.** Clicking
  `15m`/`1h`/`6h`/`24h` computed the window back from the most recent request's timestamp, so
  "15m" showed the 15 minutes ending at the last logged request (e.g. requests from 12:38 when
  the newest row was 12:53) rather than the last 15 real minutes. The preset window now uses
  `Date.now()`, matching the live-tail refresh path, which already advanced the window against
  wall-clock time — the two are now consistent. The `all` preset still spans the full loaded
  range.

## [1.1.0] — 2026-07-15

This release makes the starter fully app-agnostic and fixes real log-retention bugs.

### Changed
- **The trailing access-log request-attribute token is now configurable.** Previously the
  pattern hardcoded `%{maskedApiKey}r`; it now appends `%{<name>}r` where `name` is the new
  `log-viewer.request-attribute` property (default `apiKey`; empty disables the token). The
  attribute name is no longer baked into the starter.
- **The request-attribute value is serialized under its configured name.** `LogEntry` no
  longer exposes a fixed `maskedApiKey`/`apiKey` field; the value is flattened into the
  `/admin/logs/data` JSON (and the viewer search/detail row) under whatever
  `log-viewer.request-attribute` is set to (via `@JsonAnyGetter`). Default `apiKey` keeps the
  prior key. **Breaking** for consumers reading a fixed `maskedApiKey` JSON key or setting a
  `maskedApiKey` request attribute: use the configured name (default `apiKey`).
- **Unified log directory.** `log-viewer.access-log-directory` (default `logs`) now drives the
  catalina/error/exclusions Logback files too (via `<springProperty>` in the include), not just
  the Tomcat access log. Overriding it relocates all four streams consistently — previously it
  moved only the access log and silently emptied three viewer tabs.

### Fixed
- **Compressed access-log archives no longer grow without bound.** `access_log.*.log.gz` files
  were never pruned (their `.gz` name defeats Tomcat's `maxDays`), so the advertised retention
  was not enforced. The compression task now prunes them by age
  (`log-viewer.compression-retention-days`, default 30) and a total-size cap
  (`log-viewer.compression-total-size-cap`, default 1GB).
- **Corrupt-archive data loss.** A JVM kill mid-compress could leave a truncated `.gz` next to a
  good source, which the next run would delete. Compression now writes to a temp file and moves
  it into place, and validates an existing archive before removing the source.
- Corrected the viewer's request-attribute detail-row tooltip, which falsely claimed the value
  was masked and "never logged"; it is now app-neutral.

### Added
- `log-viewer.compression-cron` (default `0 10 0 * * *`; `-` disables), `compression-retention-days`,
  `compression-total-size-cap`, and `max-compressed-read-bytes` (default `50MB`, the previously
  hardcoded viewer gzip read guard) configuration properties.

## [1.0.6] — 2026-07-10

### Added
- **Link a 5xx access-log entry to its stack trace by shared `X-Request-Id`.** The
  catalina/error log pattern now includes `[%X{requestId:-}]`, the viewer parses it into
  `LogEntry.requestId` for server-log entries (optional — pre-existing logs still parse),
  and a new `GET /admin/logs/trace?requestId=…` endpoint returns the matching `error.log`
  entries. In the viewer, opening a 5xx access entry shows a **View stack trace** button
  that fetches and renders the trace inline. To route your own application's `ERROR` logs
  into `error.log`, reference the `WEBLOG_CATALINA_ERROR_FILE` appender (see README).

### Security
- **Log viewer: fixed a reflected XSS via the `?type=` query parameter.** The value was
  reflected verbatim into an inline `<script>` string in the served HTML. It is now
  restricted to a fixed allow-list (`access`, `catalina`, `error`, `exclusions`, default
  `access`) in `LogViewerController.view()`. Exploitation required an authenticated admin
  to open a crafted link, but the payload then ran in the admin's session on the
  log-viewer origin.
- **Exclusions log: fixed JSON log-injection / entry-forgery.** `AccessLogExclusionFilter`
  now serializes each `exclusions.log` line with Jackson instead of string concatenation.
  Previously the `ip` field (derived from the attacker-controlled `X-Forwarded-For` header)
  was written unescaped, letting a crafted header inject JSON structure and forge log
  entries — reachable even unauthenticated, since excluded paths are logged before auth.

### Fixed
- **Log viewer: bounded memory when tailing compressed logs.** `tailLinesGzip` now streams
  through a sliding window of at most `maxLines` lines instead of buffering the entire
  decompressed file. A well-compressed log expanding to hundreds of MB can no longer
  exhaust the heap. (The coarse 50 MB compressed-size guard is retained.)

### Documentation
- **Clarified how to route an application's own `ERROR` logs into the Error tab.** The
  include's appenders must be referenced by their exact `WEBLOG_`-prefixed names
  (e.g. `WEBLOG_CATALINA_ERROR_FILE`). Referencing a bare `CATALINA_ERROR_FILE` resolves to
  nothing — Logback logs `Could not find an appender named [...]` at startup and silently
  drops the routing. See README § "Configure Logback".

## [1.0.5]

- Registered Tomcat's `AccessLogValve` in code via `WebServerFactoryCustomizer` (32-token
  pattern owned by the starter — no `server.tomcat.accesslog.*` properties needed).
- Added `X-RateLimit-Limit`, `X-RateLimit-Remaining`, and `maskedApiKey` tokens to the
  access-log pattern and the viewer's parser.
- Added `server.forward-headers-strategy`-aware client-IP resolution: a trusted proxy
  setup makes the resolved `%a` remote IP win over the raw `X-Forwarded-For` header.
- Resolved the access-log directory to an absolute path against `user.dir` so access logs
  land alongside the Logback-managed files regardless of `server.tomcat.basedir`.
