# spring-boot-starter-weblog

A Spring Boot starter (Java 21, Spring Boot 3.2.4, embedded Tomcat servlet stack) that adds
four production-logging capabilities to any Spring MVC application: request-ID propagation,
access-log exclusion filtering, an in-browser log viewer, and nightly access-log compression.

This file is the entry point for AI-assisted work in this repo. [`README.md`](README.md) is
the human-facing quick-start and reference — read it first for "how do I add this to my app."
The files in [`spec/`](spec) go deeper on internals, for anyone modifying this starter itself.

## Specifications

| File | Contents |
|---|---|
| [`spec/features.md`](spec/features.md) | What each of the four components does and how, including the log viewer's client-IP resolution behavior |
| [`spec/configuration.md`](spec/configuration.md) | Every property, the two `@AutoConfiguration` classes, the Logback include file |
| [`spec/integration.md`](spec/integration.md) | How a consuming app wires this in, and the failure modes to watch for |
| [`spec/operations.md`](spec/operations.md) | Build, test, versioning, and deploying a release to GitHub Packages |

## Key files

```
src/main/java/com/eventhorizon/weblog/
├── WebLogAutoConfiguration.java           @AutoConfiguration entry point — registers everything below
│                                          (Tomcat access-log valve wired in a nested @ConditionalOnClass config)
├── WebLogSecurityAutoConfiguration.java   fallback SecurityFilterChain for /admin/logs/**, only when Spring Security is present
├── WebLogProperties.java                  @ConfigurationProperties("log-viewer")
├── LogFileNames.java                      shared rolled-log filename/date convention
├── filter/
│   ├── RequestIdFilter.java               X-Request-Id propagation (HIGHEST_PRECEDENCE)
│   ├── AccessLogExclusionFilter.java      access-log suppression + exclusions.log (HIGHEST_PRECEDENCE + 1)
│   │                                      also captures the authenticated principal (LOWEST_PRECEDENCE companion filter)
│   ├── InFlightRequestFilter.java         records in-flight requests in the registry (HIGHEST_PRECEDENCE + 2)
│   ├── BodyCaptureFilter.java             opt-in request/response body capture → bodies.log (HIGHEST_PRECEDENCE + 3)
│   ├── AuthInfoFilter.java                safe Authorization summary + deny-reason contract (HIGHEST_PRECEDENCE + 4)
│   └── ClientIp.java                      shared request-time client-IP resolution
├── inflight/
│   ├── InFlightRegistry.java              token-keyed store of currently-running requests
│   └── InFlightRequest.java               immutable snapshot of one in-flight request
├── controller/
│   ├── LogViewerController.java           /admin/logs routes, records, paging orchestration
│   ├── LogFileReader.java                 file discovery, backward tailing, gzip streaming (parser-agnostic)
│   └── LogParser.java                     text→LogEntry parsing for all four log formats
└── task/
    ├── AccessLogCompressionTask.java      nightly .log → .log.gz compression (00:10)
    └── SlowRequestWatchdog.java           logs requests still in flight past the slow threshold → slow.log
src/main/resources/
├── META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
├── META-INF/additional-spring-configuration-metadata.json
├── logback-weblog-include.xml             appenders a consuming app includes into its own logback-spring.xml
└── views/
    ├── log-viewer.html                    viewer shell (server-side [[..]] placeholders)
    ├── log-viewer.css                     viewer styles
    └── log-viewer.{core,render,modal,ui}.js  viewer script, split by concern; concatenated at serve time
```

## Scope

This module has no knowledge of, and must never reference, any specific consuming application.
Everything here — code, comments, and specs — is described in terms of "a consuming
application," not any one app's configuration or domain.

## Build & release

```bash
mvn clean test      # build + test
mvn clean install   # install to local repo for local iteration
mvn clean deploy     # publish a release to GitHub Packages — bump <version> in pom.xml first, releases are immutable
```

Full details: [`spec/operations.md`](spec/operations.md).
