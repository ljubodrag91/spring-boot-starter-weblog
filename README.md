# spring-boot-starter-weblog

A Spring Boot starter that adds production-logging capabilities to any Spring MVC application:

| Component | What it does |
|---|---|
| **RequestIdFilter** | Assigns a `X-Request-Id` UUID to every request; propagates to response header and SLF4J MDC for cross-log correlation |
| **AccessLogExclusionFilter** | Suppresses admin/Swagger paths from the Tomcat access log and writes them to the exclusions log as JSON lines; also captures the authenticated principal |
| **AuthInfoFilter** | Records a **safe summary** of the `Authorization` header (scheme, JWT `sub`/`type`/expiry — never the raw credential) plus an app-supplied deny reason, for debugging 401/403s |
| **InFlightRequestFilter** + **SlowRequestWatchdog** | Track requests currently running and write a `slow.log` line for any still in flight past the threshold — the only record of a request that never completes |
| **BodyCaptureFilter** | Opt-in (`log-viewer.body.enabled`) request/response body capture to `bodies.log`, with size caps and key redaction |
| **LogViewerController** | Serves a full-featured browser log viewer at `/admin/logs` (access, server, error, excluded, slow tabs), including paged date-ranged browsing of compressed history and a live in-flight strip |
| **AccessLogCompressionTask** | Gzip-compresses previous days' rolled log files at 00:10 nightly, with catch-up |

---

## Requirements

| Requirement | Version / Notes |
|---|---|
| Java | 21 |
| Spring Boot | 3.2.4 |
| Spring MVC (servlet stack) | included via `spring-boot-starter-web` |
| **Embedded Tomcat** | default Spring Boot servlet container; **Jetty and Undertow are not supported** |

> **Spring Boot 4 note:** this starter targets Spring Boot **3.x** and is *not* Boot 4 compatible as-is. Boot 4 moved `TomcatServletWebServerFactory` from `org.springframework.boot.web.embedded.tomcat` to `org.springframework.boot.tomcat.servlet`, and split `spring-boot-starter-web` into `spring-boot-starter-webmvc` + an explicit `spring-boot-tomcat` dependency. Moving to Boot 4 needs one import change in `WebLogAutoConfiguration` plus those two coordinate swaps — see the `1.6.0` entry in [`CHANGELOG.md`](CHANGELOG.md).

> **Reactive apps** (WebFlux) are not supported. The starter auto-configures only when `ConditionalOnWebApplication(SERVLET)` is met.

> **Tomcat dependency detail:** Three of the four components rely on Tomcat-specific behaviour:
> - `AccessLogExclusionFilter` sets a request attribute (`skipLog`) that Tomcat's `AccessLogValve` reads via `condition-unless` — this mechanism does not exist in Jetty or Undertow.
> - `AccessLogCompressionTask` gzip-compresses the rolled log files (Tomcat's access log plus the Logback catalina/error/exclusions logs, which roll to plain `.log` so this task owns their compression).
> - `LogViewerController` parses those same Tomcat-format files by fixed token position.
>
> If you replace embedded Tomcat with another container (e.g. `spring-boot-starter-jetty`), only `RequestIdFilter` will continue to work.

---

## Quick start

The artifact is published to **GitHub Packages**. Because GitHub Packages requires
authentication even for public reads, Maven needs credentials before it can resolve the
dependency — there is no anonymous access.

**`settings.xml`** (host `~/.m2/settings.xml`, or a repo-local one passed with `-s`):

```xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0
                              https://maven.apache.org/xsd/settings-1.0.0.xsd">
  <servers>
    <server>
      <id>github</id>
      <username>${env.GITHUB_USER}</username>
      <password>${env.GITHUB_TOKEN}</password>
    </server>
  </servers>
</settings>
```

`GITHUB_TOKEN` needs the `read:packages` scope to consume, or `write:packages` to publish.
Keep the token in the environment, not in the file.

---

## Integration steps

### 1. Add the dependency

In your application's `pom.xml`:

```xml
<dependency>
    <groupId>com.eventhorizon</groupId>
    <artifactId>spring-boot-starter-weblog</artifactId>
    <version>1.6.0-sb3</version>
</dependency>
```

Unlike a Nexus group repo, GitHub Packages is not reachable through a mirror, so the
consuming `pom.xml` also needs the repository declared explicitly:

```xml
<repositories>
    <repository>
        <id>github</id>
        <name>GitHub Packages — spring-boot-starter-weblog</name>
        <url>https://maven.pkg.github.com/ljubodrag91/spring-boot-starter-weblog</url>
    </repository>
</repositories>
```

The `<id>github</id>` must match the `<server>` id in your `settings.xml` (see [Quick start](#quick-start)).

---

### 2. Configure Logback

Add one `<include>` line to your `logback-spring.xml`. This wires up the three file appenders the log viewer reads:

```xml
<configuration>

    <include resource="logback-weblog-include.xml"/>

    <!-- Your existing appenders and loggers below -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] [%X{requestId:-}] %logger{50} - %msg%n</pattern>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
    </root>

</configuration>
```

The include provides three rolling appenders:

All appenders write under `log-viewer.access-log-directory` (`<dir>`, default `logs`). The appenders have no `<file>` element, so active files are dated+indexed and rolled archives are `.gz`:

| Appender writes to | Log viewer tab | Contents |
|---|---|---|
| `<dir>/exclusions.<date>.0.log[.gz]` | **Excluded** | JSON lines from `AccessLogExclusionFilter` |
| `<dir>/catalina.<date>.0.log[.gz]` | **Server Log** | Tomcat internals (INFO+) |
| `<dir>/error.<date>.0.log[.gz]` | **Error Log** | Tomcat internals (ERROR only) |

> **Already have catalina routing?** The include uses appender names prefixed `WEBLOG_` (e.g. `WEBLOG_CATALINA_FILE`) to avoid conflicts. If your existing config already routes `org.apache.catalina` to a file, the `additivity="false"` on the include's logger block will take precedence. Remove the catalina section from the include if you want to keep your own routing.

**Route your own application errors into the Error tab.** The include's appenders are a public contract — reference them by their exact `WEBLOG_`-prefixed names. To send your application's own `ERROR` logs to `error.log` (and therefore the viewer's **Error Log** tab, correlated by `requestId` via the console pattern above), add an appender-ref for your base package:

```xml
<!-- Your app's ERRORs also land in error.log, alongside Tomcat's internals -->
<logger name="com.example.myapp">
    <appender-ref ref="WEBLOG_CATALINA_ERROR_FILE"/>
</logger>
```

> ⚠️ The appender is named **`WEBLOG_CATALINA_ERROR_FILE`** — note the `WEBLOG_` prefix. Referencing a bare `CATALINA_ERROR_FILE` resolves to nothing: Logback prints `Could not find an appender named [CATALINA_ERROR_FILE]` at startup and **silently drops the routing**, so your app's errors never reach the file. If the Error tab is missing your application's entries, check startup output for that message.

---

### 3. Tomcat access log — nothing to configure

The starter registers Tomcat's `AccessLogValve` automatically via `WebServerFactoryCustomizer`. The pattern, file naming, daily rotation, and `condition-unless=skipLog` are all set in code (the valve's `maxDays` cleanup is disabled — no automatic deletion). No `server.tomcat.accesslog.*` properties are needed.

The pattern is a **34-token base** plus an **optional 35th trailing request-attribute token** (`%{<name>}r`) that is appended only when `log-viewer.request-attribute` is non-empty (empty by default — the feature is off). Tokens 31–33 are the starter's own request attributes: the authenticated `user`, the safe `auth` summary of the `Authorization` header, and the app-supplied `deny` reason. The log viewer's positional parser depends on those fixed positions.

The compression task covers every log family in `log-viewer.compression-log-prefixes` (default `access_log, catalina, error, exclusions`), gzipping each past-day file on the `log-viewer.compression-cron` schedule (default `0 10 0 * * *`); because it re-scans on every run, one run catches up the whole backlog accumulated while the app was down. The Logback catalina/error/exclusions appenders therefore roll to plain `.log` (no `.gz`) and let this task own their compression. **The task compresses only — it never deletes archives** (see [Log file lifecycle](#log-file-lifecycle--rolling--compression)); bounding disk usage is left to the operator.

The Logback include resolves the log directory via `<springProperty source="log-viewer.access-log-directory">`, and the access-log valve customizer uses the same property — so all four log streams (Tomcat access log plus the catalina/error/exclusions logs) follow `log-viewer.access-log-directory` and relocate together when you override that one property.

The only optional override is if your logs should go somewhere other than `logs/`:

```properties
# Optional — default is logs/ relative to the JVM working directory
log-viewer.access-log-directory=/var/log/myapp
```

> **Why in code, not properties?** The log viewer parser reads tokens by positional index. A pattern in a property file can be silently broken by a typo or a stray edit. Owning it inside the starter removes that risk entirely.

---

### 4. Security — automatic when Spring Security is present

If your app has `spring-boot-starter-security` on the classpath, the starter registers a fallback `SecurityFilterChain` for `/admin/logs/**` with HTTP Basic auth and `SessionCreationPolicy.ALWAYS` (required so the viewer's background `fetch()` calls reuse the session cookie). No configuration needed.

If your app has no Spring Security at all, the viewer is unprotected — no action needed.

**Existing admin chain:** if you already have a `SecurityFilterChain` that covers `/admin/**`, it takes priority (lower `@Order` wins). Make sure it uses `SessionCreationPolicy.ALWAYS`, otherwise the viewer's `fetch()` to `/admin/logs/data` will get a 401.

**Override the starter's chain:** define a bean named `weblogAdminFilterChain`:

```java
@Bean("weblogAdminFilterChain")
@Order(1)
SecurityFilterChain weblogAdminFilterChain(HttpSecurity http) throws Exception {
    return http
        .securityMatcher("/admin/logs", "/admin/logs/**")
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.ALWAYS))
        .authorizeHttpRequests(a -> a.anyRequest().authenticated())
        .httpBasic(Customizer.withDefaults())
        .build();
}
```

---

### 5. Make sure `@EnableScheduling` is active

The compression task requires `@EnableScheduling`. The starter's `WebLogAutoConfiguration` declares `@EnableScheduling` itself, so you don't need to add it unless you want it on your own `@SpringBootApplication` class. Having it in both places is harmless.

---

## Configuration reference

All properties are under the `log-viewer` prefix.

| Property | Type | Default | Description |
|---|---|---|---|
| `log-viewer.access-log-directory` | `String` | `logs` | Directory for all four log streams — the Tomcat access log **and** the catalina/error/exclusions logs (the Logback include resolves it via `<springProperty>`). Override this one property to relocate every stream together. |
| `log-viewer.excluded-prefixes` | `List<String>` | `/admin/logs`, `/swagger-ui`, `/v3/api-docs`, `/actuator` | URI prefixes (after context path) excluded from the Tomcat access log |
| `log-viewer.request-attribute` | `String` | `apiKey` | Name of the trailing per-request attribute (`%{name}r`) captured into the access log and surfaced in the `/admin/logs/data` JSON under this key. Logged verbatim; empty disables the token. |
| `log-viewer.compression-log-prefixes` | `List<String>` | `access_log, catalina, error, exclusions` | Log-filename prefixes the compression task gzips (each swept independently). |
| `log-viewer.compression-cron` | `String` | `0 10 0 * * *` | Schedule for the nightly gzip task. `-` disables it. The task compresses only — it never deletes archives. |
| `log-viewer.max-compressed-read-bytes` | `DataSize` | `50MB` | Largest compressed file the tail view will decompress (a CPU/latency guard; paged/date-ranged reads are exempt). |

### Example overrides

```properties
# Write access logs to an absolute path
log-viewer.access-log-directory=/var/log/myapp

# Add Actuator to excluded paths
log-viewer.excluded-prefixes=/admin/logs, /swagger-ui, /v3/api-docs, /actuator
```

---

## Log file lifecycle — rolling & compression

Every log file moves through the same states. Understanding this explains what the viewer's tabs show and what the nightly task does:

```
   active            rolled (plain)          archived (.gz)   ── kept indefinitely
 ┌─────────┐  roll  ┌───────────────┐  gzip ┌──────────────┐     (no automatic
 │ today's │ ─────▶ │ a past day's  │ ────▶ │ compressed   │      deletion)
 │  .log   │        │ plain .log    │       │  .log.gz     │
 └─────────┘        └───────────────┘       └──────────────┘
   written by         Tomcat / Logback         AccessLogCompressionTask (00:10 nightly)
```

Archives are **never deleted** by the starter — there is no retention/pruning. Bounding disk usage is the operator's responsibility (external rotation, disk monitoring, or manual cleanup).

### Rolling — how "active" becomes "rolled"

Two different writers roll the four log families, on different triggers:

| Family | Writer | Rolls when | Active-file name | Notes |
|---|---|---|---|---|
| **access** | Tomcat `AccessLogValve` | **new calendar day** | `access_log.<date>.log` | One file per day. No size limit, no compression by Tomcat. |
| **catalina / error / exclusions** | Logback `SizeAndTimeBasedRollingPolicy` | **new day *or* `maxFileSize`** | `catalina.<date>.<i>.log` | The `<i>` is a within-day size index (`.0`, `.1`, …). `maxFileSize` is 50 MB (catalina) / 20 MB (error, exclusions). |

Crucially, the starter configures the Logback appenders to roll to **plain `.log`** — no `.gz`, no `maxHistory`/`totalSizeCap`. Compression is handed entirely to the nightly task (next section). That's deliberate: Logback (and Tomcat) only compress *at the moment a rollover happens while the JVM is running*. On an app that's shut down over midnight, that moment is missed and files would orphan as uncompressed `.log` forever.

### Compression — the nightly `AccessLogCompressionTask`

Runs at `log-viewer.compression-cron` (default `0 10 0 * * *` = 00:10). For **every** prefix in `log-viewer.compression-log-prefixes` (default `access_log, catalina, error, exclusions`) it does a **directory sweep**: compress every past-day `<prefix>.<date>[.<i>].log` → `.log.gz`, then delete the plain source. "Past-day" is decided by the date in the filename (strictly before today), so today's active file is left alone and undated legacy files (e.g. a bare `catalina.log`) are skipped.

It does **not** prune: `.log.gz` archives are kept indefinitely. Manage disk usage externally.

**Catch-up is the whole point of the sweep.** Because it re-scans the directory on every run (not just "yesterday's file"), a single run compresses the entire backlog of plain files that piled up on nights the app was down. So the task self-heals: as long as the app is up at 00:10 on *some* night, history gets compressed — no matter how many midnights it missed.

### File names by state

| State | access | catalina / error / exclusions |
|---|---|---|
| **active** (today) | `access_log.2026-07-16.log` | `catalina.2026-07-16.0.log` |
| **rolled** (past day, not yet swept) | `access_log.2026-07-15.log` | `catalina.2026-07-15.0.log` |
| **archived** (after the task runs) | `access_log.2026-07-15.log.gz` | `catalina.2026-07-15.0.log.gz` |

All four viewer tabs read **both** `.log` and `.log.gz` transparently, so history stays visible after compression.

### Knobs

| Property | Default | Controls |
|---|---|---|
| `log-viewer.compression-log-prefixes` | `access_log, catalina, error, exclusions` | Which families the task sweeps |
| `log-viewer.compression-cron` | `0 10 0 * * *` | When it runs (`-` disables) |

The Logback per-file `maxFileSize` values live in the `logback-weblog-include.xml` you import — adjust them there if you want larger/smaller size-rolled server-log files.

---

## Accessing the viewer

Navigate to `http://localhost:8080/{context-path}/admin/logs` (replace `{context-path}` with your `server.servlet.context-path`).

Tabs available:

Source files live under `log-viewer.access-log-directory` (`<dir>`, default `logs`); active files are dated and rolled archives are `.gz`. The viewer matches both the dated and legacy filename forms:

| Tab | Source file | What you see |
|---|---|---|
| **Access Log** | `<dir>/access_log.<date>.log[.gz]` | All API requests (newest first), filterable by status, method, IP, URI |
| **Excluded** | `<dir>/exclusions.<date>.log[.gz]` | Admin/Swagger requests suppressed from the main access log |
| **Server Log** | `<dir>/catalina.<date>.log[.gz]` | Tomcat internal messages (INFO+) |
| **Error Log** | `<dir>/error.<date>.log[.gz]` | Tomcat internal messages (ERROR only) |

Use the line-count dropdown to control how many lines are loaded (2 000 – 20 000). The viewer streams data from `/admin/logs/data` so the initial page load is always fast.

### Finding entries — search, filters & history

Finding a log entry happens in **two layers**. Getting this distinction is the key to using the viewer effectively:

**Layer 1 — choose what's *loaded* (server-side).** This decides which entries the server sends to the browser. You have two modes, and they are mutually exclusive:

| Mode | Control | What it loads | Endpoint |
|---|---|---|---|
| **Live tail** (default) | Line-count dropdown (2 000–20 000); **Live tail** toggles auto-refresh | The newest N lines of the current tab, newest-first. Fast — reads only the tail of the files. | `/admin/logs/data` |
| **History** | Top-bar **🕘 History** → from/to date + **◀ Older / Newer ▶** | One bounded page inside a date window, paged through the archives (including `.gz`). | `/admin/logs/page` |

History is how you reach **old, already-compressed** data. It selects files by the date in their name, opens them lazily newest-first, and returns one page at a time — so browsing a whole month costs the same memory as browsing one day (bounded to your page size). Blank date bounds = unbounded (all history). See [Log file lifecycle](#log-file-lifecycle--rolling--compression) for what "archived" means.

**Layer 2 — refine what's *loaded* (client-side, instant).** Everything in the sidebar and histogram filters the batch already in the browser — no server round-trip:

- **Free-text search** (`/` box): matches uri / ip / ua / requestId (access) or message / logger / thread / level / requestId (server).
- **Facets**: click **Status**, **Method**, or **Top URIs** to filter; click again to clear.
- **Histogram brush**: click a time bucket to zoom the visible time range; click again to clear.
- **Sort**: click a column header.

> **The mental model that avoids confusion:** *search and facets only look at what's already loaded — they never reach back into history.* If a search comes up empty, you're probably filtering a batch that doesn't contain the data yet. To search older entries, first **load** them (raise the line count, or switch to **History** and page to the right window), then refine. Because it's a file-scanning viewer, there is no full-history text index — loading-then-refining is by design.

**Access-log 5xx → stack trace.** Click a 5xx access-log row and choose **View stack trace**: the viewer joins it to the matching `error` log entry by shared `X-Request-Id`, scoped to that row's date so it resolves even when the error has already rotated to `.gz`.

> **Client IP shown in the Access Log tab:** the viewer prefers the raw connection address
> (`%a`) over the `X-Forwarded-For`/`X-Real-IP` headers **only if** your app has
> `server.forward-headers-strategy` configured (e.g. `native`, so Tomcat's `RemoteIpValve`
> validates the header before trusting it). Without that property set, behavior is unchanged:
> `X-Forwarded-For` (first value if comma-separated) → `X-Real-IP` → `%a`. Set
> `server.forward-headers-strategy` once you have a reverse proxy in front and want the
> viewer to show a spoof-resistant client IP instead of an unvalidated header value.

> **Rate-limit headers:** if your app sets `X-RateLimit-Limit`/`X-RateLimit-Remaining` on its
> responses, the access log now captures both (`rateLimitLimit`/`rateLimitRemaining` in the
> `/admin/logs/data` JSON) — apps that don't set these headers just get `null` for both,
> nothing else changes. Not yet surfaced as dedicated columns in the viewer's table UI, only
> in the underlying data.

> **Logging a per-request attribute (e.g. an API key):** the access log appends one trailing
> `%{name}r` request-attribute token, where `name` is `log-viewer.request-attribute`
> (default `apiKey`; empty disables the token). Your app sets it per request via
> `request.setAttribute("apiKey", value)`, and the parsed value appears in the
> `/admin/logs/data` JSON under the same key (`apiKey`), in the viewer's detail panel under
> **Request**, and in free-text search. Prefer a request attribute over a request-*header*
> token (`%{name}i`) for such values: a header is captured verbatim with no chance to
> transform it, whereas the app fully controls what it puts in the attribute. Note the value
> is still written **verbatim** — if you want it masked, mask it in app code before setting
> the attribute; the starter does not mask.

> **Detail-panel sections are subtly color-coded** (Facts / Network / Request / Response —
> click a row to open) purely as a visual aid; the accent only tints each section's divider
> line, not the text or background, keeping it subtle rather than a strong color-coding scheme.

---

## How `X-Request-Id` works

1. `RequestIdFilter` runs first (`HIGHEST_PRECEDENCE`).
2. If the incoming request carries a valid `X-Request-Id` header (alphanumeric + `-_`, max 64 chars), it is reused. Otherwise a UUID is generated.
3. The ID is:
   - Written to the response header `X-Request-Id` → captured at token[28] in the Tomcat access log.
   - Written to SLF4J MDC key `requestId` → appears in every Logback line as `[%X{requestId:-}]`.
   - Written to the exclusions log JSON as `"requestId":"..."`.

All four log files (access, catalina, error, exclusions) share the same request ID, so you can grep for one UUID across all files.

---

## Releasing a new version

The artifact is published to GitHub Packages at
`https://maven.pkg.github.com/ljubodrag91/spring-boot-starter-weblog`
(`com.eventhorizon:spring-boot-starter-weblog`). Published versions are immutable —
GitHub Packages rejects redeploying an existing version. To publish a new one:

### 1 — Bump the version

In `pom.xml`, change `<version>` to the next release. Update the version in any consumer apps at the same time.

### 2 — Tag and push (preferred)

`.github/workflows/publish.yml` publishes on any pushed `v*` tag, using the workflow's own
`GITHUB_TOKEN` — no local credentials involved:

```bash
git tag v1.6.0-sb3 && git push origin v1.6.0-sb3
```

The workflow can also be run manually from the Actions tab (`workflow_dispatch`).

### 3 — Or deploy locally

Requires a `settings.xml` whose `<server><id>github</id></server>` carries a token with
`write:packages` (see [Quick start](#quick-start)); the id must match the
`distributionManagement` repository id in `pom.xml`.

```bash
mvn clean deploy
```

---

## Project layout

```
spring-boot-starter-weblog/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/eventhorizon/weblog/
    │   │   ├── WebLogAutoConfiguration.java     # @AutoConfiguration entry point
    │   │   ├── WebLogSecurityAutoConfiguration.java # fallback /admin/logs/** chain, only when Spring Security is present
    │   │   ├── WebLogProperties.java            # @ConfigurationProperties("log-viewer")
    │   │   ├── LogFileNames.java                # shared rolled-log filename/date convention
    │   │   ├── filter/
    │   │   │   ├── RequestIdFilter.java         # X-Request-Id propagation
    │   │   │   ├── AccessLogExclusionFilter.java# Access log suppression + exclusions.log + principal capture
    │   │   │   ├── AuthInfoFilter.java          # Safe Authorization summary + deny reason
    │   │   │   ├── BodyCaptureFilter.java       # Opt-in request/response body capture
    │   │   │   ├── InFlightRequestFilter.java   # Records in-flight requests in the registry
    │   │   │   └── ClientIp.java                # Shared request-time client-IP resolution
    │   │   ├── inflight/
    │   │   │   ├── InFlightRegistry.java        # Token-keyed store of running requests
    │   │   │   └── InFlightRequest.java         # Immutable snapshot of one in-flight request
    │   │   ├── controller/
    │   │   │   ├── LogViewerController.java     # /admin/logs routes, records, paging
    │   │   │   ├── LogFileReader.java           # Discovery, backward tailing, gzip streaming
    │   │   │   └── LogParser.java               # text→LogEntry parsing for every log format
    │   │   └── task/
    │   │       ├── AccessLogCompressionTask.java# Nightly .log → .log.gz compression
    │   │       └── SlowRequestWatchdog.java     # Slow/never-completing requests → slow.log
    │   └── resources/
    │       ├── META-INF/spring/
    │       │   └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
    │       ├── META-INF/additional-spring-configuration-metadata.json
    │       ├── logback-weblog-include.xml       # Logback include file
    │       └── views/                           # Viewer frontend (no external JS/CDN deps)
    │           ├── log-viewer.html               #   HTML shell (carries server [[..]] placeholders)
    │           ├── log-viewer.css                #   styles (served as a static asset)
    │           └── log-viewer.{core,render,modal,ui}.js  #   script, split by concern; concatenated at serve time
    └── test/
        └── java/com/eventhorizon/weblog/
```

---

## Development workflow

```bash
# Build + test
mvn clean test

# Install to local Maven repo (useful for quick local iteration without deploying)
mvn clean install

# Deploy a release to GitHub Packages (bump version in pom.xml first — releases are immutable)
mvn clean deploy
```

---

## How it works — component details

### RequestIdFilter

Runs at `HIGHEST_PRECEDENCE`. Accepts client-supplied IDs matching `[a-zA-Z0-9\-_]{1..64}`. Rejects everything else (whitespace, quotes, braces) to prevent log injection. Always sets `X-Request-Id` on the response so the correlation ID is visible to clients.

### AccessLogExclusionFilter

Runs at `HIGHEST_PRECEDENCE + 1` (after `RequestIdFilter`). Sets `req.setAttribute("skipLog", TRUE)` which Tomcat's `AccessLogValve` checks via `condition-unless=skipLog`. Also writes a compact JSON line to `com.eventhorizon.weblog.exclusions` logger.

### LogViewerController

Mounted at `/admin/logs`. Reads log files from `log-viewer.access-log-directory` (defaults to `logs`) — the same directory the access-log valve and the Logback appenders write to. All four tabs read both `.log` (active) and `.log.gz` (compressed) files through one unified path. Plain files are read backwards in 8 KB chunks; compressed files are streamed through a bounded sliding window — heap stays bounded to the requested line count regardless of file size. On the tail view, compressed files are size-guarded (default 50 MB, `log-viewer.max-compressed-read-bytes`) as a CPU/latency guard (gzip can't seek, so reading a `.gz`'s tail must inflate the whole file); larger archives are skipped there. The paged history endpoint (`/admin/logs/page`) is exempt from that guard and reads archives live. The page's CSS/JS are served as separate static, ETag-cached assets at `/admin/logs/log-viewer.css` and `/admin/logs/log-viewer.js`.

### AccessLogCompressionTask

Scheduled at `0 10 0 * * *` (00:10:00). For every prefix in `log-viewer.compression-log-prefixes` (access + catalina/error/exclusions), scans for past-day `<prefix>.<date>[.<i>].log` files and gzip-compresses them. **No retention** — archives are never deleted. Ten minutes after midnight gives the writer time to finish and release the file handle (important on Windows). The scan re-runs each night, so files orphaned while the app was down are caught up in a later run.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Access log tab shows no data | Wrong log directory | Check `log-viewer.access-log-directory` matches where Tomcat writes; verify `server.tomcat.basedir` is set to your working directory |
| All access log lines fail to parse | Two `AccessLogValve` instances | Remove all `server.tomcat.accesslog.*` properties — if `server.tomcat.accesslog.enabled=true` is set, Spring Boot adds a second valve with a different pattern |
| Viewer returns 401 | Auth not configured | Ensure `/admin/logs/**` is in your `SecurityFilterChain` |
| Viewer returns 404 | Controller not registered | Check `ConditionalOnWebApplication` — app must be servlet-based (not WebFlux) |
| Excluded tab is empty | Logback include missing | Ensure `<include resource="logback-weblog-include.xml"/>` is in your `logback-spring.xml` |
| `X-Request-Id` is not in Tomcat access log | Pattern token[21] missing | Verify pattern contains `"%{X-Request-Id}i"` at position 21 and `"%{X-Request-Id}o"` at position 28 |
| `@Scheduled` task never runs | `@EnableScheduling` missing | The starter adds it automatically. If using `spring.main.lazy-initialization=true`, disable lazy init for the starter beans or remove the flag |

---

## License

Internal use — EventHorizon.
