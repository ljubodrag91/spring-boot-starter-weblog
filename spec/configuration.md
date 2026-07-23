# Configuration

## Properties (`log-viewer` prefix)

Declared on `WebLogProperties` (`@ConfigurationProperties("log-viewer")`), enabled via
`@EnableConfigurationProperties(WebLogProperties.class)` on `WebLogAutoConfiguration`.

| Property | Type | Default | Read by |
|---|---|---|---|
| `log-viewer.access-log-directory` | `String` | `logs` | `LogViewerController`, `AccessLogCompressionTask`, the `AccessLogValve` customizer, **and** the Logback include (via `<springProperty>`) — all four write/read this one path |
| `log-viewer.excluded-prefixes` | `List<String>` | `/admin/logs`, `/swagger-ui`, `/v3/api-docs`, `/actuator` | `AccessLogExclusionFilter` — kept out of the access log, still audited in `exclusions.log` |
| `log-viewer.silent-prefixes` | `List<String>` | `/admin/logs/inflight`, `/admin/logs/data`, `/admin/logs/page`, `/admin/logs/trace`, `/admin/logs/log-viewer.` | `AccessLogExclusionFilter` — quieter tier: suppressed from the access log **and** `exclusions.log` (recorded nowhere). Checked first, so a path matching both tiers is silenced. Default silences the viewer's own self-traffic; add e.g. `/actuator/health` for probe noise |
| `log-viewer.request-attribute` | `String` | `apiKey` | `WebLogAutoConfiguration` (appends `%{name}r` to the pattern) + `LogViewerController` (serializes the value under this JSON key). Empty disables the token |
| `log-viewer.compression-log-prefixes` | `List<String>` | `access_log`, `catalina`, `error`, `exclusions` | `AccessLogCompressionTask` — the log families it compresses, each swept independently |
| `log-viewer.compression-cron` | `String` | `0 10 0 * * *` | `AccessLogCompressionTask` `@Scheduled`; `-` disables. Compresses only — never deletes archives (no retention) |
| `log-viewer.max-compressed-read-bytes` | `DataSize` | `50MB` | `LogViewerController` — skips decompressing files larger than this |

IDE hints for the prefix-list properties are declared in
`META-INF/additional-spring-configuration-metadata.json` (needed because Lombok-generated
getters/setters aren't visible to the annotation processor at the point it scans the class).

Also read, but **not** a `log-viewer.*` property — it's the consuming app's own standard Spring
Boot setting: `server.forward-headers-strategy`, read directly via `@Value` in
`LogViewerController` to decide client-IP trust (see `spec/features.md` → "Client IP
resolution").

## `WebLogAutoConfiguration`

The `@AutoConfiguration` entry point. Conditions: `@ConditionalOnWebApplication(SERVLET)` —
this starter only activates for servlet-stack Spring MVC apps, never WebFlux. Responsibilities:

- Registers `RequestIdFilter` and `AccessLogExclusionFilter` via `@Import`.
- Declares `LogViewerController` and `AccessLogCompressionTask` as plain `@Bean` methods
  (not `@Import`) — both classes carry no Spring annotations themselves, so this is the one
  place Spring processes their `@Value`/`@PostConstruct` members.
- Registers a `WebServerFactoryCustomizer<TomcatServletWebServerFactory>` (guarded by
  `@ConditionalOnClass(TomcatServletWebServerFactory.class)`) that configures Tomcat's
  `AccessLogValve` in code: the fixed 31-token base pattern the log viewer's parser depends on
  (plus an optional trailing `%{name}r` request-attribute token when `log-viewer.request-attribute`
  is set), `access_log` prefix, `.log` suffix, daily rotation, and
  `condition-unless=skipLog`. A consuming app sets no `server.tomcat.accesslog.*` properties
  at all — doing so anyway creates a **second** valve with a different pattern and breaks
  parsing (see `spec/integration.md` → troubleshooting).
- Declares `@EnableScheduling` so `AccessLogCompressionTask` fires without the consuming app
  needing it on its own `@SpringBootApplication` class.

The access-log pattern is a `static final String` constant, not a property, specifically
because the parser reads tokens by fixed index — a pattern editable via a property file could
be silently broken by a stray edit, with no compile-time signal.

## `WebLogSecurityAutoConfiguration`

A separate, independently-conditioned `@AutoConfiguration` class:
`@ConditionalOnClass(SecurityFilterChain.class)` — only activates when the consuming app
already depends on `spring-boot-starter-security` (declared `<optional>true</optional>` here,
so it's never pulled in transitively).

Registers a `SecurityFilterChain` named `weblogAdminFilterChain` at
`@Order(Integer.MAX_VALUE - 5)` — the lowest meaningful priority, so any `SecurityFilterChain`
the consuming app defines for `/admin/**` at a normal `@Order` takes precedence and this
fallback is never reached. Covers `/admin/logs`, `/admin/logs/**` with HTTP Basic and
`SessionCreationPolicy.ALWAYS` (required so the viewer's background `fetch()` calls reuse the
session cookie — browsers don't reliably re-send Basic credentials on XHR/fetch). A consuming
app can override entirely by defining its own bean named `weblogAdminFilterChain`
(`@ConditionalOnMissingBean(name = "weblogAdminFilterChain")` backs this one off).

An app with no Spring Security on the classpath at all gets neither chain — the viewer is
simply unprotected, no configuration needed either way.

## Logback include (`logback-weblog-include.xml`)

Not Java config — an `<included>` Logback fragment a consuming app pulls in via
`<include resource="logback-weblog-include.xml"/>` in its own `logback-spring.xml`. Provides
three async, size-and-time-rolling file appenders that roll to **plain `.log`** (no `.gz`,
no `maxHistory`/`totalSizeCap`); compression is owned by `AccessLogCompressionTask`, which
sweeps these prefixes exactly as it does the access log (compress-only — no deletion):

| Logger routed | File (active + rolled files are dated plain `.log`; `.gz` produced by the compression task) | Consumed by (viewer tab) |
|---|---|---|
| `com.eventhorizon.weblog.exclusions` | `<dir>/exclusions.<date>.<i>.log[.gz]` | Excluded |
| `org.apache.catalina` / `org.apache.tomcat` / `org.apache.coyote` | `<dir>/catalina.<date>.<i>.log[.gz]` (INFO+), `<dir>/error.<date>.<i>.log[.gz]` (ERROR only) | Server Log / Error Log |

`<dir>` is `log-viewer.access-log-directory` (default `logs`), bound into the include via
`<springProperty>` so the catalina/error/exclusions files follow the same directory as the
Tomcat access log. The appenders have no `<file>` element, so the active file is the dated
pattern name (`.0.log`); past days are gzipped to `.log.gz` by the nightly compression task,
not by Logback. Keeping `.gz`/`maxHistory` out of the appenders is deliberate — see the
`AccessLogCompressionTask` section in `features.md` for why the task owns this instead of
Logback's rollover.

All three loggers set `additivity="false"` so their output doesn't also flow into a consuming
app's root/console appender. Appender names are prefixed `WEBLOG_` to avoid colliding with a
consuming app's own appender names if it already routes `org.apache.catalina` somewhere.
