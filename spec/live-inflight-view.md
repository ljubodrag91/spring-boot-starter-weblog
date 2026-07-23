# Design: Live in-flight request view (merged) — Phase 2

> **Status:** proposal for review. Phase 1 (in-flight registry + slow-request watchdog → `slow.log`)
> is built and released in **1.4.0**; this document specifies the Phase 2 UI/endpoint that turns the
> same registry into a live, merged request view. Nothing here is implemented yet.
>
> **Scope note (decoupling):** everything below is generic Spring MVC / servlet behaviour. No
> consumer-application specifics belong in this feature or its docs.

## Motivation

The Tomcat access log writes a line **only when a request completes**. So three classes of request
are invisible or misleading in it:

1. **Still in flight right now** — no line exists yet.
2. **Never completes** (hung worker, thread/JVM death) — no line ever.
3. **Completed only after the client abandoned it** — logged, but with a completion status/`connFate`
   that can read as a clean success (e.g. `+`) when the response fit the socket buffer before the
   abort was noticed.

Phase 1 addresses (2) and (3) *after the fact*: the watchdog persists a `SLOW` line to `slow.log` for
any request in flight past a threshold, so there is a durable trace even when no one was watching.

Phase 2 addresses (1) and makes the whole picture **one screen**: a live view of what is running *now*,
merged with the completed-request history, so an operator watches a single pane instead of
cross-referencing `access_log` and `slow.log` by hand.

## Non-goals (explicitly rejected)

- **Do not replace the Tomcat access log.** It remains the authoritative *completion* record — it is
  free, battle-tested, and provides `connFate`, `%D`, TTFB, and the fixed token record that clients and
  tools already parse. We do not hand-roll start+end logging to supplant it.
- **Do not write a log line at the start of every request.** That doubles log volume for a read-heavy
  service where ~99% of requests finish in milliseconds. The live layer is **in-memory only**; only
  slow/never-completed requests are ever *persisted* (Phase 1's `slow.log`).
- **Do not mutate log files.** Log files are append-only; a "row that updates on completion" cannot be
  a file line. The update model lives in memory (the registry) and is reconciled *on read* by
  `requestId`, never by rewriting a line.

## Architecture

```
InFlightRegistry (in memory, already exists)
   ├─ written by InFlightRequestFilter  (entry → register, completion → remove)
   ├─ swept by  SlowRequestWatchdog      (persists SLOW lines to slow.log)   ← Phase 1
   └─ read by   LogViewerController      (GET /admin/logs/inflight, snapshot) ← Phase 2 (new)

Viewer merges three sources by requestId into one screen:
   • in-flight (live, from /inflight)   — running now
   • access log (completion record)     — finished
   • slow.log (persisted SLOW trace)    — was slow / never finished
```

The registry is the single source of "what's running"; it already exists and costs ~1–3µs and ~80 bytes
per request. Phase 2 adds a **read-only** endpoint over it plus viewer rendering — no new tracking cost.

## New endpoint: `GET /admin/logs/inflight`

- **Auth / exclusion:** under the existing `/admin/logs/**` chain (HTTP Basic in the consuming app).
  Because the frontend polls this every `inflight-refresh` (default 3s) while the viewer is open, its path
  is a default `log-viewer.silent-prefixes` entry — suppressed from the access log, from `exclusions.log`,
  and from the in-flight registry alike. So the poll cannot show up in its own live view (no feedback loop)
  and never pollutes any log stream. (Shipped 1.4.3; 1.4.0–1.4.2 excluded it from the access log and
  registry only, so the 3s poll flooded `exclusions.log`.)
- **Cost:** serializes the current map (bounded by the worker pool, typically ≤ a few hundred entries).
  No disk I/O.
- **Response:** newest-longest-first array plus server context, e.g.:

```json
{
  "now": "2026-07-22 11:15:04.812",
  "thresholdMs": 30000,
  "count": 2,
  "requests": [
    {"requestId":"…","method":"GET","uri":"/api/…","ip":"…",
     "thread":"http-nio-8081-exec-3","startedAt":"2026-07-22 11:15:01.257",
     "inFlightMs":8165,"slow":false}
  ]
}
```

- `inFlightMs` is computed server-side from `System.nanoTime()` at snapshot time (never from the client
  clock). `slow = inFlightMs >= thresholdMs` drives amber highlighting client-side. Sorted by
  `inFlightMs` descending so the most-stuck request is at the top.

### Registry support

Add a read-only projection to `InFlightRegistry` (or compute in the controller) that maps each
`InFlightRequest` to the DTO above with `inFlightMs = (now - startNanos)/1e6`. `InFlightRequest` already
carries everything needed (`requestId`, `method`, `uri`, `ip`, `thread`, `startNanos`, `startEpochMs`).

## Viewer changes

- A **live "In-flight" section** pinned above the access-log table on the request view, auto-refreshing
  on a short interval (poll `/inflight`). Rows: age (live), method, URI, IP, thread, requestId. Rows with
  `slow=true` are highlighted amber — those are the stuck/timed-out ones, visible *as they happen*.
- On completion a request drops out of `/inflight` and appears in the access-log table on the next `/data`
  refresh; the two are the same `requestId`. The viewer may annotate an access row whose `requestId` also
  appeared in `slow.log` (a "was slow" badge), giving the merged picture without touching the files.
- **Refresh mechanism — recommend polling.** The current viewer is stateless and poll-based; a 2–3s poll
  of a tiny JSON payload fits it with no new infrastructure. Server-Sent Events would give lower-latency
  updates but adds a streaming endpoint, connection lifecycle, and proxy considerations — deferred unless
  the poll proves insufficient.

## Configuration (proposed, `log-viewer.*`)

Reuses Phase 1's `slow-request-threshold` for the amber cutoff. New, optional:

| Property | Default | Purpose |
|---|---|---|
| `inflight-view-enabled` | `true` | Master switch for the endpoint + live section. |
| `inflight-refresh` | `3s` | Client poll interval for the live section (served into the page). |

Env-overridable via relaxed binding as with all `log-viewer.*` settings.

## Security / privacy

- URIs (incl. query strings) and IPs are shown — identical exposure to the existing viewer tabs, which
  already display these. No *new* class of data is revealed; the endpoint is behind the same admin auth.
- `ip` derives from `X-Forwarded-For` (attacker-influenceable) but is only displayed, never trusted for a
  decision. Values are serialized via Jackson (escaped), consistent with the exclusion/slow writers.
- The endpoint must never be exposed unauthenticated — it reveals in-progress request URIs.

## Edge cases

- **Lingering entry after thread death.** If a worker dies without running its `finally`, its entry stays
  and shows an ever-growing age — which is *desirable*: that is exactly a stuck/dead request surfacing.
  A JVM restart clears the in-memory registry. Per decision 2 there is **no eviction cap**; the registry
  is naturally bounded to `threads.max` by `finally`, and `/inflight` exposes `count` so any pathological
  growth would be observable rather than silently capped (which could hide a real hang).
- **Excluded paths** (log viewer, Swagger) are already skipped by `InFlightRequestFilter` (via the
  `skipLog` attribute), so tooling traffic never appears in the live view.
- **Clock skew** is a non-issue: age is server-computed and sent as `inFlightMs`.

## Relationship to existing viewer features

- The **"slow lane"** (1.3.0) ranks *completed* requests by duration from the access log — covers
  "completed but slow." The live view covers "running / never completed." Together with `slow.log`
  (persisted forensics for when no one watched), the three are complementary, not redundant.

## Resolved decisions (2026-07-22)

1. **Refresh → polling, 3s, configurable.** No SSE. The view watches for tens-of-seconds hangs, so
   sub-second latency buys nothing; nginx buffers SSE by default; polling is stateless and fits the
   existing viewer. Interval served into the page via `inflight-refresh` (default `3s`); an operator can
   drop it lower while actively investigating.
2. **No eviction cap; expose the count instead.** The registry is naturally bounded to
   `server.tomcat.threads.max` (~200) because each worker holds at most one entry and its `finally`
   removes it before the thread's next request — so a leak requires `finally` to repeatedly fail, which
   servlet flow does not do. A time cap's only real-world effect would be to *hide* a genuine multi-minute
   hang (the alarm), and `slow.log` already persists the record regardless. Instead, `/inflight` returns
   `count` (registry size) so a hypothetical leak would be *observable* (the number climbing past ~200).
3. **Placement → always-on, inline, merged.** A live in-flight strip pinned atop the request/access view
   (not a separate tab — that would re-split what we're merging). Polls only while the page is open (i.e.
   while investigating). Zero-state: when `count == 0`, collapse to a thin `In-flight: 0` bar; expand to
   rows the moment something is running, with anything past threshold in amber. Request/access view only.
4. **`slow.log` → yes, add a dedicated "Slow" tab.** The live strip is the *during-incident* half; the
   Slow tab is the *after-the-fact* half — and after-the-fact is how investigation actually happens here
   (this whole feature was found *after* the timeouts, not while watching). A never-completed request has
   no access-log line to correlate against, so `slow.log` is its only home; it must be browsable in the UI
   rather than requiring `docker exec` + `grep`. Cheap: mirror the existing `exclusions` JSON-tab path.
   Sort by `inFlightMs` descending.
