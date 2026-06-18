# spring-boot-starter-weblog

A Spring Boot starter that adds four production-logging capabilities to any Spring MVC application:

| Component | What it does |
|---|---|
| **RequestIdFilter** | Assigns a `X-Request-Id` UUID to every request; propagates to response header and SLF4J MDC for cross-log correlation |
| **AccessLogExclusionFilter** | Suppresses admin/Swagger/Actuator paths from the Tomcat access log and writes them to `logs/exclusions.log` as JSON lines (including the authenticated user, when Spring Security is present) |
| **BodyCaptureFilter** (opt-in) | Captures request/response bodies into `logs/bodies.log` with redaction + size cap; exposed in the viewer modal on-demand |
| **LogViewerController** | Serves a full-featured browser log viewer at `/admin/logs` (4 tabs: access, server, error, excluded) |
| **AccessLogCompressionTask** | Gzip-compresses Tomcat access log files at 00:10 nightly |

---

## Requirements

| Requirement | Version / Notes |
|---|---|
| Java | 25 |
| Spring Boot | 4.0.5 |
| Spring MVC (servlet stack) | included via `spring-boot-starter-webmvc` |
| **Embedded Tomcat** | default Spring Boot servlet container; **Jetty and Undertow are not supported** |

> **Spring Boot 4.0 note:** `TomcatServletWebServerFactory` moved from `org.springframework.boot.web.embedded.tomcat` to `org.springframework.boot.tomcat.servlet`. The starter declares `spring-boot-tomcat` as an explicit optional dependency for this reason — it is not pulled in transitively by `spring-boot-starter-webmvc` in Spring Boot 4.x.

> **Reactive apps** (WebFlux) are not supported. The starter auto-configures only when `ConditionalOnWebApplication(SERVLET)` is met.

> **Tomcat dependency detail:** Three of the four components rely on Tomcat-specific behaviour:
> - `AccessLogExclusionFilter` sets a request attribute (`skipLog`) that Tomcat's `AccessLogValve` reads via `condition-unless` — this mechanism does not exist in Jetty or Undertow.
> - `AccessLogCompressionTask` gzip-compresses access log files written by Tomcat's `AccessLogValve`.
> - `LogViewerController` parses those same Tomcat-format files by fixed token position.
>
> If you replace embedded Tomcat with another container (e.g. `spring-boot-starter-jetty`), only `RequestIdFilter` will continue to work.

---

## Quick start

The artifact is published to **GitHub Packages** at `https://maven.pkg.github.com/ljubodrag91/spring-boot-starter-weblog`. Even for public packages, GitHub requires authentication on read — every consumer needs a Personal Access Token with `read:packages`.

**`~/.m2/settings.xml`** on the consumer host (or in your CI runner — e.g. Render's build environment):

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

Set the env vars `GITHUB_USER` (any GitHub username with access) and `GITHUB_TOKEN` (PAT with at minimum `read:packages`). On Render, add both as secret environment variables on the service. The `<id>github</id>` must match the `<id>` in the consumer pom's `<repositories>` block (see [Add the dependency](#1-add-the-dependency)).

---

## Integration steps

### 1. Add the dependency

In your application's `pom.xml`:

```xml
<dependency>
    <groupId>com.eventhorizon</groupId>
    <artifactId>spring-boot-starter-weblog</artifactId>
    <version>1.1.0-sb3</version>
</dependency>
```

And declare the GitHub Packages repo at the top of the same `pom.xml` so Maven knows where to look:

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/ljubodrag91/spring-boot-starter-weblog</url>
        <snapshots><enabled>true</enabled></snapshots>
    </repository>
</repositories>
```

Credentials for `<id>github</id>` come from `~/.m2/settings.xml` (see [Quick start](#quick-start)) — never hard-code them in `pom.xml`.

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

| Appender writes to | Log viewer tab | Contents |
|---|---|---|
| `logs/exclusions.log` | **Excluded** | JSON lines from `AccessLogExclusionFilter` |
| `logs/catalina.log` | **Server Log** | Tomcat internals (INFO+) |
| `logs/error.log` | **Error Log** | Tomcat internals (ERROR only) |

> **Already have catalina routing?** The include uses appender names prefixed `WEBLOG_` (e.g. `WEBLOG_CATALINA_FILE`) to avoid conflicts. If your existing config already routes `org.apache.catalina` to a file, the `additivity="false"` on the include's logger block will take precedence. Remove the catalina section from the include if you want to keep your own routing.

---

### 3. Tomcat access log — nothing to configure

The starter registers Tomcat's `AccessLogValve` automatically via `WebServerFactoryCustomizer`. The 29-token pattern, file naming, rotation, 30-day retention, and `condition-unless=skipLog` are all set in code. No `server.tomcat.accesslog.*` properties are needed.

The starter resolves the log directory against the JVM working directory (`user.dir`) — the same anchor Logback uses — so access logs always land alongside `catalina.log` and `exclusions.log` regardless of `server.tomcat.basedir`.

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
| `log-viewer.access-log-directory` | `String` | `logs` | Directory where Tomcat access log files are written. Relative paths resolve against `catalina.base` (`server.tomcat.basedir`). |
| `log-viewer.excluded-prefixes` | `List<String>` | `/admin/logs`, `/swagger-ui`, `/v3/api-docs`, `/actuator` | URI prefixes (after context path) excluded from the Tomcat access log |
| `log-viewer.body.enabled` | `boolean` | `false` | Master switch for request/response body capture. Off by default — bodies can contain secrets. |
| `log-viewer.body.max-bytes` | `int` | `8192` | Per-side cap. Oversize bodies are truncated and the captured JSON line carries `"reqTruncated":true` / `"resTruncated":true`. |
| `log-viewer.body.redact-keys` | `List<String>` | `password`, `secret`, `token`, `authorization`, `apiKey`, `api_key` | Case-insensitive JSON keys whose values are replaced with `"***"` before write. |
| `log-viewer.body.skip-content-types` | `List<String>` | `multipart/`, `image/`, `video/`, `audio/`, `application/octet-stream` | Content-type prefixes for which bodies are not captured at all (heap-safe skip for binary/multipart payloads). |

### Example overrides

```properties
# Write access logs to an absolute path
log-viewer.access-log-directory=/var/log/myapp

# Override the default excluded paths (the example below matches the default)
log-viewer.excluded-prefixes=/admin/logs, /swagger-ui, /v3/api-docs, /actuator

# Opt in to request/response body capture
log-viewer.body.enabled=true
log-viewer.body.max-bytes=16384
log-viewer.body.redact-keys=password, secret, token, authorization, sessionId
```

### Capturing the authenticated user

When Spring Security is on the consumer's classpath, the starter records the
authenticated principal's name (`Authentication#getName()` — typically the
email or username) into a request attribute and includes a `"user"` field on
every exclusion JSON line. The viewer surfaces this in the modal under the
**Request** section as *Authenticated user*. No configuration required —
when Spring Security is absent, the field is simply omitted.

### Capturing request/response bodies

Opt in with `log-viewer.body.enabled=true`. The `BodyCaptureFilter` then wraps
every request with `ContentCachingRequestWrapper` /
`ContentCachingResponseWrapper`, writes one JSON line per request to
`logs/bodies.log`, and the viewer lazily fetches bodies via
`/admin/logs/body?requestId=…` whenever you open the modal for a request.
Each captured line is shaped like:

```json
{"ts":"2026-06-18 14:32:01.502","requestId":"abc","method":"POST",
 "uri":"/api/v1/login","status":200,
 "reqCT":"application/json","reqBytes":47,
 "reqBody":"{\"email\":\"u@example.com\",\"password\":\"***\"}",
 "resCT":"application/json","resBytes":1200,"resBody":"{\"token\":\"***\"}"}
```

> Caveat: redaction is a flat regex replace on `"key":"value"` pairs. It does
> not understand nested JSON, gRPC, or form-encoded bodies. Review the bodies
> log before sharing it externally — and prefer leaving body capture off in
> production unless you've vetted your redact list.

---

## Accessing the viewer

Navigate to `http://localhost:8080/{context-path}/admin/logs` (replace `{context-path}` with your `server.servlet.context-path`).

Tabs available:

| Tab | Source file | What you see |
|---|---|---|
| **Access Log** | `logs/access_log.*.log` / `.log.gz` | All API requests (newest first), filterable by status, method, IP, URI |
| **Excluded** | `logs/exclusions.log` | Admin/Swagger requests suppressed from the main access log |
| **Server Log** | `logs/catalina.log` | Tomcat internal messages (INFO+) |
| **Error Log** | `logs/error.log` | Tomcat internal messages (ERROR only) |

Use the line-count dropdown to control how many lines are loaded (2 000 – 50 000). The viewer streams data from `/admin/logs/data` so the initial page load is always fast.

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

The artifact lives on **GitHub Packages** under the repo `ljubodrag91/spring-boot-starter-weblog`. The recommended flow is tag-driven via GitHub Actions — no local `mvn deploy` needed.

### 1 — Bump the version

In `pom.xml`, change `<version>` to the next release (e.g. `1.0.1-sb3`). Update the version in any consumer apps at the same time.

### 2 — Tag and push

```bash
git commit -am "Release 1.0.1-sb3"
git tag v1.0.1-sb3
git push origin main --tags
```

`.github/workflows/publish.yml` watches for tags matching `v*` and runs `mvn deploy` from an Ubuntu runner using the built-in `GITHUB_TOKEN`. The deployed artifact appears under the repo's *Packages* tab within a couple of minutes.

### 3 — (Manual fallback) Deploy from your machine

Only needed if Actions is down. Generate a PAT with `write:packages` scope and add it to `~/.m2/settings.xml`:

```xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <servers>
    <server>
      <id>github</id>
      <username>ljubodrag91</username>
      <password>YOUR_PAT_HERE</password>
    </server>
  </servers>
</settings>
```

The `<id>github</id>` must match the `distributionManagement` id in `pom.xml`. Then:

```bash
mvn clean deploy
```

GitHub Packages **does not allow re-deploying the same version** — bump the version every time, or delete the existing package version from the GitHub UI first.

---

## Project layout

```
spring-boot-starter-weblog/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/eventhorizon/weblog/
    │   │   ├── WebLogAutoConfiguration.java     # @AutoConfiguration entry point
    │   │   ├── WebLogProperties.java            # @ConfigurationProperties("log-viewer")
    │   │   ├── filter/
    │   │   │   ├── RequestIdFilter.java         # X-Request-Id propagation
    │   │   │   └── AccessLogExclusionFilter.java# Access log suppression + exclusions.log
    │   │   ├── controller/
    │   │   │   └── LogViewerController.java     # /admin/logs viewer endpoint
    │   │   └── task/
    │   │       └── AccessLogCompressionTask.java# Nightly .log → .log.gz compression
    │   └── resources/
    │       ├── META-INF/spring/
    │       │   └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
    │       ├── META-INF/additional-spring-configuration-metadata.json
    │       ├── logback-weblog-include.xml       # Logback include file
    │       └── views/log-viewer.html            # Viewer frontend (no external JS deps)
    └── test/
        └── java/com/eventhorizon/weblog/
            ├── filter/
            │   ├── RequestIdFilterTest.java
            │   └── AccessLogExclusionFilterTest.java
            └── controller/
                └── LogViewerControllerTest.java
```

---

## Development workflow

```bash
# Build + test
mvn clean test

# Install to local Maven repo (useful for quick local iteration without deploying)
mvn clean install

# Deploy a release to Nexus (bump version in pom.xml first — releases are immutable)
mvn clean deploy
```

---

## How it works — component details

### RequestIdFilter

Runs at `HIGHEST_PRECEDENCE`. Accepts client-supplied IDs matching `[a-zA-Z0-9\-_]{1..64}`. Rejects everything else (whitespace, quotes, braces) to prevent log injection. Always sets `X-Request-Id` on the response so the correlation ID is visible to clients.

### AccessLogExclusionFilter

Runs at `HIGHEST_PRECEDENCE + 1` (after `RequestIdFilter`). Sets `req.setAttribute("skipLog", TRUE)` which Tomcat's `AccessLogValve` checks via `condition-unless=skipLog`. Also writes a compact JSON line to `com.eventhorizon.weblog.exclusions` logger.

### LogViewerController

Mounted at `/admin/logs`. Reads log files from `${server.tomcat.accesslog.directory}` (defaults to `logs`). Handles both `.log` (active) and `.log.gz` (compressed) access log files. Reads backwards in 8 KB chunks for efficiency — no full file load. Gzip files are size-guarded at 50 MB compressed to prevent heap exhaustion.

### AccessLogCompressionTask

Scheduled at `0 10 0 * * *` (00:10:00). Scans for `access_log.*.log` files that don't match today's date and gzip-compresses them. Ten minutes after midnight gives Tomcat time to finish writing and release the file handle (important on Windows).

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

Internal use — eventhorizon.
