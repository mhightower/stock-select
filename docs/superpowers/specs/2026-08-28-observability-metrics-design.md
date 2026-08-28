# Observability & Metrics — Design

## Goal

Add production-ready observability to stock-select: Prometheus metrics
export via Micrometer, business-level metrics for the screening flow and
vendor calls, and structured log fields for key request metadata —
without adding unnecessary frameworks or restructuring the app.

## Context

`spring-boot-starter-actuator` is already a dependency, and pulls in
Micrometer core (1.17.1) transitively via
`spring-boot-starter-micrometer-metrics` — no `MeterRegistry` wiring is
needed, only a registry implementation for Prometheus. The bundled
Logback (1.5.38) and SLF4J (2.0.18) already support everything needed for
structured key-value log fields (`%kvp` pattern token, fluent
`addKeyValue` API) — no logging framework change needed.

`/health` already exists at the root path (`management.endpoints.web.base-path: /`,
see `health/CLAUDE.md`) with liveness/readiness groups and
`show-details: always`, and must be left untouched.

## Dependency change

Add `micrometer-registry-prometheus` to `pom.xml` (runtime scope, no
version — managed by `spring-boot-starter-parent`). This is the only new
dependency.

## Endpoint exposure

`management.endpoints.web.base-path` stays `/` so `/health` is unaffected.
The new endpoints are remapped under `/actuator` via
`management.endpoints.web.path-mapping`:

```yaml
management:
  endpoints:
    web:
      base-path: /
      exposure:
        include: health, prometheus, metrics, info
      path-mapping:
        prometheus: actuator/prometheus
        metrics: actuator/metrics
        info: actuator/info
```

Result: `GET /health` (unchanged), `GET /actuator/prometheus`,
`GET /actuator/metrics`, `GET /actuator/info`. `exposure.include` is an
explicit allowlist, not `*` — no endpoints beyond these four are exposed
over HTTP.

Also add `spring.application.name: stock-select` (currently unset) and
`management.metrics.tags.application: ${spring.application.name}` as a
common tag on every exported metric — standard Boot practice, useful the
moment there's more than one service's metrics in the same Prometheus
instance.

## Business metrics

All under a `stockselect.*` namespace:

| Metric | Type | Tags | Requirement covered |
|---|---|---|---|
| `stockselect.screen.requests` | Counter | `strategy`, `outcome` (`success`/`failure`) | request count, per-strategy counts, success/failure rate |
| `stockselect.screen.latency` | Timer | `strategy`, `outcome` | screen latency |
| `stockselect.vendor.calls` | Counter | `vendor`, `outcome` | vendor call count, vendor failure rate |
| `stockselect.vendor.ratelimit.remaining` | Gauge | `vendor` | MarketData.app rate limit remaining |

No percentile histograms on the timers — count/sum is sufficient for rate
and average latency in Prometheus/Grafana; buckets are a cheap follow-up
if p95/p99 is ever needed.

### Cardinality guard

The `strategy` tag must never echo the raw path variable directly: an
unknown-strategy request (`/api/screen/whatever/AAPL`) would otherwise
create a new, unbounded Prometheus label value per garbage input. Unknown
strategies are tagged with a fixed `strategy=unknown` sentinel instead;
valid values are bounded to the registered `TradeStrategy` beans (3
today). `vendor` tags come from each client's own `VENDOR` constant
(`"EODHD"` / `"MarketData.app"`), never user input.

### Instrumentation points

- `ScreeningService` gains a `MeterRegistry` constructor parameter (same
  DI pattern already used for its other collaborators). `screen()`'s body
  is wrapped in try/finally so the counter and timer are recorded
  regardless of outcome — including the unknown-strategy early return,
  which increments the counter directly before throwing (no timer, since
  no work happened).
- `EodhdClient` / `MarketDataClient` gain a `MeterRegistry` constructor
  parameter. The vendor-call counter and a latency timer are recorded
  right next to the existing `VendorHealthTracker.recordSuccess`/
  `recordFailure` calls in `doOnSuccess`/`doOnError` — same scope as
  today's health tracking (only `UpstreamApiException` after error
  mapping, matching existing behavior, not a new gap).
- `MarketDataClient`'s constructor registers
  `stockselect.vendor.ratelimit.remaining` as a function-backed
  `Gauge` reading `healthTracker.rateLimitRemaining(VENDOR)` — reflects
  current state on every scrape, no push-based bookkeeping.

## Structured logging

No MDC. `ScreeningService` runs across a virtual-thread-per-task executor
(`Executors.newVirtualThreadPerTaskExecutor()`), and the vendor clients'
`doOnSuccess`/`doOnError` callbacks run on Reactor/Netty threads — MDC's
thread-local storage does not reliably cross either boundary, so relying
on it would silently drop fields in production. Instead, SLF4J 2.x's
fluent API (`log.atInfo().addKeyValue(...).log(...)`) is used at the
exact point each field is known:

- `ScreeningService`: one line per completed request (success or
  failure) — `strategy`, `symbol`, `status`, `latencyMs`.
- `EodhdClient` / `MarketDataClient`: one line per vendor call —
  `vendor`, `status`, `latencyMs`.

`logging.pattern.console` in `application.yml` is redefined (not layered
onto Boot's colored default) to end with `%kvp`, the Logback token that
renders fluent key-value pairs — they're silently dropped by the default
pattern otherwise. Dropping ANSI color codes in the process is a
reasonable tradeoff for containerized log output, which usually gets
stripped or reformatted by a log collector anyway.

## Testing

- `ScreeningServiceTest`: inject a `SimpleMeterRegistry`; add assertions
  that a successful screen records
  `stockselect.screen.requests{strategy=...,outcome=success}` and
  `stockselect.screen.latency{strategy=...,outcome=success}`; an
  unknown-strategy request records
  `stockselect.screen.requests{strategy=unknown,outcome=failure}`; a
  vendor-failure propagation records `outcome=failure` under the real
  strategy tag.
- `EodhdClientTest` / `MarketDataClientTest`: inject a
  `SimpleMeterRegistry`; assert `stockselect.vendor.calls` increments
  with the right `vendor`/`outcome` tags on both the success and
  failure/timeout paths already covered in each file.
  `MarketDataClientTest` additionally asserts the rate-limit gauge value
  after `recordRateLimit` fires.
- `StockSelectApplicationTests` (the one full-context boot test) is
  extended to hit `/actuator/prometheus` and `/actuator/metrics` and
  assert `200`, alongside its existing `/` and `/health` checks — this is
  the test that would catch a Prometheus-registry autoconfiguration or
  path-mapping wiring failure.

## Files touched

`pom.xml`, `application.yml`, `ScreeningService.java`, `EodhdClient.java`,
`MarketDataClient.java`, `ScreeningServiceTest.java`, `EodhdClientTest.java`,
`MarketDataClientTest.java`, `StockSelectApplicationTests.java`.

## Explicitly out of scope

- No new `@Configuration` class — `PrometheusMeterRegistry` autoconfigures
  once the dependency is on the classpath; nothing to wire by hand.
- No auth on the new endpoints — the app has no Spring Security dependency
  today and `/health` is already unauthenticated; adding auth is a
  larger, separate decision (flagged as a follow-up recommendation, not
  implemented here).
- No JSON logging / log shipping — text + `%kvp` was chosen over adding
  `logstash-logback-encoder` to keep this change dependency-light; JSON
  logs are a clean follow-up if a log aggregator is adopted later.
- No percentile histograms / SLO objectives on the timers.
