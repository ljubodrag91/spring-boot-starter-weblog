# Integration

Full step-by-step is in [`README.md`](../README.md) → "Integration steps." This file covers
the failure modes and non-obvious interactions worth knowing before touching this starter.

## What a consuming app must NOT do

- **Set any `server.tomcat.accesslog.*` property.** `WebLogAutoConfiguration` registers its
  own `AccessLogValve` in code with a fixed 31-token base pattern (plus an optional trailing
  request-attribute token) the log viewer's parser depends on by position. If the consuming app also sets `server.tomcat.accesslog.enabled=true` (or
  any other `accesslog.*` property), Spring Boot's own auto-configuration adds a **second**
  valve with a different, incompatible pattern — every access-log line then fails to parse.
- **Rely on `X-Real-IP`/`X-Forwarded-For` for anything security-sensitive without also
  configuring `server.forward-headers-strategy`.** The log viewer's own IP resolution follows
  whatever the consuming app has configured there (see `spec/features.md`) — it doesn't
  independently validate the headers itself.

## What breaks silently if skipped

| Missing step | Symptom |
|---|---|
| `<include resource="logback-weblog-include.xml"/>` not added | Excluded/Server Log/Error Log tabs stay empty forever — no error, just nothing to read |
| `SessionCreationPolicy.ALWAYS` not set on a consuming app's own `/admin/**` chain (if it defines one, overriding the fallback) | Viewer page loads, but its background `fetch()` to `/admin/logs/data` gets 401 — browsers don't reliably resend HTTP Basic credentials on XHR/fetch without a session |
| `@EnableScheduling` conflicts (rare) | `AccessLogCompressionTask` never runs; `.log` files accumulate uncompressed. `WebLogAutoConfiguration` already declares it, so this only bites if something explicitly disables scheduling elsewhere |
| App's own ERROR logs not routed to the error appender | The viewer's "click a 5xx → view stack trace" join finds nothing for exceptions your app catches and logs itself — `error.log` only captures `org.apache.*` ERRORs (Tomcat internals + `StandardWrapperValve` uncaught-exception logging). To cover your own exceptions, route your base package's ERRORs to `WEBLOG_ASYNC_CATALINA_ERROR` in your `logback-spring.xml` (see the snippet in `logback-weblog-include.xml`'s header) |

## Auto-configuration exclusion

A consuming app that defines its own `SecurityFilterChain` for `/admin/**` at a normal
`@Order` doesn't need to exclude anything — `WebLogSecurityAutoConfiguration`'s fallback chain
sits at the lowest possible priority (`Integer.MAX_VALUE - 5`) and is simply never reached.
Excluding it explicitly (`spring.autoconfigure.exclude=com.eventhorizon.weblog.WebLogSecurityAutoConfiguration`)
is only necessary if a consuming app hits a startup ordering conflict between the two chains —
harmless either way, since the fallback chain does nothing once superseded.

## Reactive apps

Not supported. `WebLogAutoConfiguration` only activates under
`@ConditionalOnWebApplication(SERVLET)`. Three of the four components are Tomcat-specific
(`AccessLogExclusionFilter`'s `skipLog` attribute, `AccessLogCompressionTask`'s target files,
`LogViewerController`'s parser) — swapping the embedded container to Jetty or Undertow leaves
only `RequestIdFilter` functional.
