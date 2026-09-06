# Request Correlation IDs & Latency Percentiles — Design

## Goal

Close two gaps left by the existing observability work
([2026-08-28-observability-metrics-design.md](./2026-08-28-observability-metrics-design.md)):

1. There is no way to tie the one `ScreeningService` "screen completed" log
   line to the `EodhdClient`/`MarketDataClient` "vendor call completed"
   log lines it triggered — under concurrent traffic, log lines from
   different in-flight requests interleave with no way to reconstruct
   which vendor calls belong to which incoming request.
2. `stockselect.screen.latency` has no percentile/SLO configuration (the
   prior spec explicitly deferred this), and vendor-call latency isn't a
   metric at all today — `EodhdClient`/`MarketDataClient` only increment a
   `Counter` and log the elapsed time, so it's not queryable as p95/p99 in
   Prometheus.

## Context

MDC is not viable for correlation here — already established in
`observability/CLAUDE.md`-equivalent reasoning (see the prior spec's
"Structured logging" section): `ScreeningService` fans out onto
`Executors.newVirtualThreadPerTaskExecutor()`, and the vendor clients'
`doOnSuccess`/`doOnError` callbacks run on Reactor/Netty threads. MDC's
thread-local storage does not reliably cross either boundary. Any
correlation ID has to be threaded through explicitly as a method
parameter, the same way `ScreeningService` already captures
`vendorSymbol` as a final local for its lambdas.

## Correlation ID propagation

### New component: `web/CorrelationIdFilter`

A `@Component` implementing `OncePerRequestFilter`, registered ahead of
`ScreeningController` in the filter chain (default Spring Boot ordering —
no explicit `@Order` needed since nothing else in this app is a filter).
For every request:

1. Read the `X-Request-Id` request header. If present and non-blank, use
   it as-is (honors a caller's own correlation ID for end-to-end tracing
   across their system and this one).
2. If absent, generate `UUID.randomUUID().toString()`.
3. Set it as the `X-Request-Id` **response** header immediately, before
   calling `filterChain.doFilter(...)` — this guarantees the header is
   present on every response this app produces, including the existing
   `NoHandlerFoundException` 404 path and any path that never reaches
   `ScreeningController`, matching this app's existing philosophy that no
   response should fall through uncleanly (`ApiExceptionHandler`'s
   `NoHandlerFoundException` handling is the precedent).
4. Store it as a request attribute (`request.setAttribute("requestId", id)`)
   for `ScreeningController` to read.

A filter (not a `HandlerInterceptor`) specifically because filters run
for every request regardless of whether `DispatcherServlet` finds a
handler — an interceptor's `preHandle` would not fire for the 404 case.

### `web/ScreeningController`

Reads `(String) request.getAttribute("requestId")` and passes it as a new
final argument to `ScreeningService.screen(symbol, strategyName, requestId)`.
Needs a `HttpServletRequest` parameter added to the handler method (Spring
MVC injects it automatically).

### `screening/ScreeningService`

`screen(String symbol, String strategyName, String requestId)` — new
third parameter. Passed straight through to:

- `marketDataClient.getOptionsChain(vendorSymbol, requestId)`
- `eodhdClient.getQuote(vendorSymbol, requestId)`

and added as `addKeyValue("requestId", requestId)` on the existing
"screen completed" log line (`ScreeningService.java:96-101`). The
unknown-strategy early-return path (`ScreeningService.java:45-53`) has no
log line today (a pre-existing gap, not introduced or fixed by this
change) — left as-is.

### `eodhd/EodhdClient` and `marketdata/MarketDataClient`

`getQuote(String symbol, String requestId)` and
`getOptionsChain(String symbol, String requestId)` — new second
parameter on each. Added as `addKeyValue("requestId", requestId)` on each
client's existing "vendor call completed" log line.

### Response contract change

`GET /api/screen/{strategy}/{symbol}` (and every other endpoint, since
the filter is global) now always returns an `X-Request-Id` response
header. This is an additive contract change — no existing header or body
shape changes.

## Latency percentiles / SLOs

Both new and existing timers use `.publishPercentileHistogram()` rather
than `.publishPercentiles(...)`: the former emits Prometheus histogram
buckets (`_bucket`/`_count`/`_sum`) that `histogram_quantile()` can
aggregate correctly across instances in PromQL; the latter computes
percentiles client-side per instance, which cannot be validly aggregated
and is a well-known Micrometer-with-Prometheus footgun. Explicit SLO
buckets, shared by both metrics (screen latency is roughly
`max(vendor calls) + strategy compute time`, so the same range fits
both): 250ms, 500ms, 1s, 2s, 5s.

- `stockselect.screen.latency` (`ScreeningService.java:91-95`): add
  `.publishPercentileHistogram()` and
  `.serviceLevelObjectives(Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(5))`
  to the existing `Timer.builder(...)` chain. Tags (`strategy`, `outcome`)
  unchanged.
- `stockselect.vendor.latency` (new): registered in each vendor client's
  `recordVendorCall`, alongside the existing `stockselect.vendor.calls`
  counter, same tags (`vendor`, `outcome`) and same percentile/SLO config
  as above.

## Testing

- `CorrelationIdFilterTest` (new): inbound `X-Request-Id` is echoed
  unchanged; absent header produces a generated UUID on the response;
  request attribute is set to the same value used in the response header.
- `ScreeningControllerTest`: assert `X-Request-Id` is present on both a
  successful `200` response and an error response (e.g. unknown
  strategy's `400`).
- `ScreeningServiceTest`, `EodhdClientTest`, `MarketDataClientTest`: every
  existing call site of `screen(...)`, `getQuote(...)`,
  `getOptionsChain(...)` needs the new parameter threaded through
  (mechanical). Add one assertion per class that the new parameter value
  reaches the corresponding log line or, for the vendor clients, that
  `stockselect.vendor.latency` recorded a sample with the right `vendor`/
  `outcome` tags.
- `StockSelectApplicationTests`: `CorrelationIdFilter` has no
  `urlPatterns` restriction, so it applies to every path including `/`
  and `/health`, not just `/api/**`. Add an assertion to the existing
  `/health` check that the response carries an `X-Request-Id` header,
  confirming the filter is wired at the whole-context level and not just
  reachable in the `ScreeningController` slice tests.

## Files touched

New: `web/CorrelationIdFilter.java`, `web/CorrelationIdFilterTest.java`.

Modified: `web/ScreeningController.java`, `screening/ScreeningService.java`,
`eodhd/EodhdClient.java`, `marketdata/MarketDataClient.java`,
`ScreeningServiceTest.java`, `EodhdClientTest.java`,
`MarketDataClientTest.java`, `ScreeningControllerTest.java`.

No `pom.xml` or `application.yml` changes — no new dependency, and
percentile histogram config is done in code via `Timer.builder(...)`, not
YAML.

## Explicitly out of scope

- Distributed tracing (`micrometer-tracing`/OpenTelemetry) — this app is
  a single service calling two vendor APIs, not a multi-hop system;
  revisit if that changes.
- Prometheus alerting rules / Grafana dashboards — different kind of
  deliverable (infra artifacts, not application code), tracked separately
  if wanted.
- Fixing the unknown-strategy path's missing log line — pre-existing gap
  noticed during this design, not introduced here; flagged as a possible
  follow-up, not fixed as a drive-by in this change.
- Authentication on any endpoint — unchanged from the prior spec's stance.
