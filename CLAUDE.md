# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Spring Boot service that pulls a stock quote from [EODHD](https://eodhd.com/)
and its option chain from [MarketData.app](https://www.marketdata.app/), then
screens them for options-selling trade candidates, behind a `TradeStrategy`
interface so more strategies can be added without touching existing code.
Three strategies exist today: the **Jade Lizard** (short call + short put
vertical spread), the **Bull Put Spread** (short put vertical spread, no
call leg — a strict subset of the Jade Lizard, and the simplest of the
three to read as a reference for adding another), and the **Bear Call
Spread** (short call vertical spread, no put leg — the Bull Put Spread's
mirror image on the call side).

**Why two data sources:** EODHD's options chain endpoint
(`/api/mp/unicornbay/options/eod`) is a paid marketplace add-on not included
on the free plan; MarketData.app has a permanent free tier ("Free Forever":
listed as 100 requests/day, though the `x-api-ratelimit-*` response headers
show a 10,000/day pool in practice) that includes real option chains with
Greeks — just 24h delayed. EODHD's quote endpoint stayed since it already
worked on the free plan. **Symbol format matters**: MarketData.app rejects
exchange-suffixed symbols like `AAPL.US` outright (`"Symbol not found"`)
while EODHD accepts the bare ticker fine — `ScreeningService` normalizes to
the bare uppercased ticker before calling either client.

**MarketData.app's quota is metered per contract returned, not per HTTP
call** — confirmed live (2026-08-21): a pre-batch check showed 9,725/10,000
remaining, and just 10 sequential `/api/screen/*` calls (one options-chain
fetch each, covering the app's 1-75 DTE window) against liquid mega-caps
(AAPL, MSFT, NVDA, AMZN, GOOGL, META, TSLA, AMD, JPM, DIS) drained it to 0 —
roughly 970 units/call, consistent with each chain response containing
hundreds of contracts. In practice this means **~10 chain screens/day** on
the free tier, not 100 or 10,000 — budget test batches accordingly, and
check `x-api-ratelimit-remaining` (or the `x-api-ratelimit-reset` epoch
timestamp for when it refills) before running more than a couple of
symbols through `/api/screen/*` in one session. In response,
`MarketDataClient`'s fetch window was narrowed from 1-75 to 25-65 DTE (see
the `marketdata/` entry below) — cuts the contracts fetched per call
without dropping anything any strategy actually uses, though the quota-per-
contract behavior itself is a vendor characteristic this doesn't eliminate,
just reduces the impact of.

## Toolchain

Use the Maven Wrapper (`./mvnw`), not a system `mvn` — it self-bootstraps
the exact Maven version (`.mvn/wrapper/maven-wrapper.properties`) on first
run, no separate Maven install needed. This is also what CI uses.

This environment does not have a JDK on `PATH` by default (apt's Ubuntu
22.04 repos don't carry JDK 26), so that part still needs manual setup —
Temurin JDK 26 is installed locally under `~/tools`:

```bash
export JAVA_HOME=~/tools/jdk-26.0.2+10
export PATH="$JAVA_HOME/bin:$PATH"
```

## Commands

```bash
./mvnw test                                    # unit tests only — no API keys needed
./mvnw test -Dtest=JadeLizardStrategyTest      # run a single test class
./mvnw test -Dtest=JadeLizardStrategyTest#picksLegsAndComputesCreditWhenRuleIsSatisfied  # single test method
./mvnw verify                                  # unit tests + integration tests (needs both API keys, see below)
./mvnw -DskipTests package                     # build the jar without running tests
./mvnw spring-boot:run                         # run the service (needs both API keys set)
```

The app needs both API tokens at runtime:

```bash
export EODHD_API_KEY=your-eodhd-api-token
export MARKETDATA_API_KEY=your-marketdata-app-api-token
```

Query it via `GET /api/screen/{strategy}/{symbol}`, e.g.
`curl http://localhost:8080/api/screen/jade-lizard/AAPL` — use the bare
ticker (`AAPL`, not `AAPL.US`). Returns `{"candidates": [...], "warnings": [...]}`
(`ScreeningResult`), not a bare array — see the EODHD degradation note below.

`GET /health` reports whether each vendor is reachable — see "Health check"
under Architecture for why it doesn't make its own vendor calls.

## Testing

JUnit 5, AssertJ, and Mockito come from `spring-boot-starter-test`.
`org.wiremock:wiremock-standalone` (test scope) mocks both EODHD's and
MarketData.app's HTTP responses in `EodhdClientTest`/`MarketDataClientTest`
so the clients are verified against the real response shapes, not just
hand-built DTOs. `ScreeningControllerTest` uses `@WebMvcTest` (Spring
Boot 4 moved this to `org.springframework.boot.webmvc.test.autoconfigure`
and requires the `spring-boot-starter-webmvc-test` test dependency) with
`@MockitoBean` from `spring-test` — Boot 4 removed the older `@MockBean`.

**Coverage:** `jacoco-maven-plugin` runs on every `./mvnw test` (bound to
the `test` phase, not `verify`) and enforces a minimum of 80% overall line
coverage (`BUNDLE`/`LINE`/`COVEREDRATIO`) — the build fails if coverage
drops below that. HTML report: `target/site/jacoco/index.html`. Coverage is
measured from unit tests only — integration tests run in a later phase and
aren't counted.

**Unit vs. integration tests:** `*Test.java` classes are unit tests
(Surefire, `test` phase) — fully isolated, WireMock stands in for both
EODHD and MarketData.app, no network or API keys needed, and they're what
`./mvnw test`/the 80% gate/CI runs. `*ClientIT.java` classes (`EodhdClientIT`,
`MarketDataClientIT`) are integration tests (Failsafe, `integration-test`/
`verify` phases) that call the real vendor APIs to catch drift WireMock
mocks can't — e.g. the MarketData.app symbol-format and buffer-size issues
below were both found this way, not by unit tests. Each is gated behind
`@EnabledIfEnvironmentVariable` on its API key and skips cleanly (not fails)
when that key isn't set, so `./mvnw verify` is safe to run without
credentials — it just skips the ITs. To actually exercise them: `source .env`
(`set -a`/`set +a`) then `./mvnw verify`. Follow the same `*ClientIT` pattern
for any new vendor client.

**CI:** `.github/workflows/ci.yml` runs `./mvnw test` (unit tests only —
deliberately not `verify`, to avoid burning MarketData.app's free-tier daily
quota on every push) on push/PR to `master`, and uploads the JaCoCo HTML
report as a build artifact. `.github/dependabot.yml` opens weekly PRs for
both Maven and GitHub Actions dependency updates.

## Architecture

**Data flow:** `ScreeningController` → `ScreeningService` → `EodhdClient`
(quote) + `MarketDataClient` (full option chain) → the matching
`TradeStrategy` bean (picks legs, prices them, returns `TradeCandidate`s).

- `eodhd/` — talking to EODHD for the quote only. `EodhdClient` wraps a
  `WebClient` and exposes `getQuote(symbol)` (flat JSON from
  `/api/real-time/{symbol}`).
- `marketdata/` — talking to MarketData.app for the option chain.
  `MarketDataClient.getOptionsChain(symbol)` requests a `from`/`to` date
  range (~25-65 DTE by default, matching the 30-60 DTE band every current
  strategy actually uses, plus a small buffer — narrowed from an original
  1-75 window after discovering MarketData.app meters this endpoint per
  contract returned, not per HTTP call, so fetching a wider range than any
  strategy uses just burns quota on contracts that get filtered out
  immediately) and maps the response into `OptionContract`. The wire format is
  **parallel arrays** (`OptionsChainResponse`: every field is a `List`,
  index `i` across all lists describes one contract) rather than an array
  of objects — very different from EODHD's shape, which is the whole
  reason the mapping lives in its own client rather than being folded into
  a generic "options DTO." Expiration dates arrive as Unix-epoch seconds
  and must be converted using `America/New_York` (not UTC, or the
  calendar date shifts) since that's the zone the exchange's 4pm/4:15pm
  close times are anchored to. The `marketDataWebClient` bean raises
  `WebClient`'s default 256KB in-memory buffer to 10MB in
  `WebClientConfig` — a real chain response blows past the default and
  throws `DataBufferLimitException` at request time, not at startup.
- `health/` — **health check** (`GET /health`, moved off the Actuator
  default `/actuator/health` via `management.endpoints.web.base-path: /`
  in `application.yml`; the built-in `diskSpace` indicator is also turned
  off there via `management.health.diskspace.enabled: false` — irrelevant
  noise for a stateless API with no local storage). `VendorHealthTracker` is passive — it never makes
  its own vendor calls. Instead `EodhdClient`/`MarketDataClient` report the
  outcome of every real call they already make (`.doOnSuccess(...)` /
  `.doOnError(UpstreamApiException.class, ...)` right where they map
  errors) into the tracker, and `HealthConfig`'s two `HealthIndicator`
  beans just read the last-recorded outcome. **This is deliberate, not a
  shortcut**: EODHD's free tier is 20 requests/day and MarketData.app's is
  100/day (see `/api/user` and the vendor docs), so a health check that
  pinged either vendor on its own schedule — which is how liveness/readiness
  probes are normally hit, every few seconds — would exhaust those quotas
  in minutes and break the app's actual function. The tradeoff: a vendor
  shows `UNKNOWN` in `/health` until the first real request touches it.
  EODHD's indicator uses a custom `DEGRADED` status (not `DOWN`) on
  failure — a status Spring Boot's default `StatusAggregator` doesn't rank
  above `UP`, so it never drags the aggregate `/health` status down, unlike
  MarketData.app's indicator which uses the standard `DOWN`. This mirrors
  `ScreeningService`'s own EODHD-is-optional/MarketData-is-required
  distinction (see below) — MarketData.app doesn't appear to validate its
  bearer token at all in practice (confirmed live: a garbage token still
  returns `200` with real data), so its `DOWN` path is only reachable via
  a real outage or rate limit, not a bad key.
- Root package (`com.stockselect`) — `UpstreamApiException` wraps any
  `WebClientResponseException` from either vendor client with which vendor
  it came from; `EodhdClient`/`MarketDataClient` map to it via
  `.onErrorMap(WebClientResponseException.class, ...)` right after
  `bodyToMono`. `web/ApiExceptionHandler` (`@RestControllerAdvice`)
  translates it into a clean JSON `{"error": "..."}` body: 429 from a
  vendor stays 429, 401/403 (bad key/entitlement) becomes 502, anything
  else also 502. Without this, a vendor error (e.g. MarketData.app's free
  tier is 100 req/day and does get hit) surfaced as a raw 500 with a full
  stack trace in the response. It also catches `NoHandlerFoundException`
  for the same reason — any unmapped path (including `/`, before
  `RootController` existed) otherwise fell through to Spring Boot's
  Whitelabel HTML error page. That requires
  `spring.mvc.throw-exception-if-no-handler-found: true` and
  `spring.web.resources.add-mappings: false` in `application.yml`, or
  DispatcherServlet swallows the 404 instead of throwing it.
- `strategy/` — the extension point, and where `OptionContract` (the app's
  vendor-neutral option model, populated by whichever client fetched it)
  and `Quote` live conceptually. `TradeStrategy` is the interface every
  strategy implements (`name()` + `evaluate(StrategyContext)`).
  `ScreeningService` autowires `List<TradeStrategy>` and indexes them by
  `name()`, so **a new strategy only needs to exist as a `@Component`** —
  no registry to update. `TradeCandidate` is the shared output shape;
  strategies that don't use a given leg (e.g. no long put) leave that field
  null rather than the shape being subclassed per-strategy. All prices are
  per-share, not per-contract (multiply by 100 for actual dollars on a
  standard equity contract) — the options-market convention, and how both
  vendors already quote bid/ask/mid. `currency` is hardcoded `"USD"` rather
  than read from either vendor's response, since neither actually includes
  a currency field; correct as long as this app stays scoped to US-listed
  equities/options (MarketData.app rejects non-US symbols outright anyway).
- `strategy/jadelizard/` — the first strategy. `JadeLizardStrategy` picks an
  expiration inside the configured DTE window closest to the target DTE,
  picks the short call/put whose delta is closest to the configured target
  delta, picks the long put as the next strike down from the short put, and
  only returns a candidate when credit received is at least
  `minCreditToWidthRatio × putSpreadWidth` (the standard Jade Lizard
  construction rule, configured in `application.yml` under
  `strategy.jade-lizard`). `JadeLizardProperties` is a `@ConfigurationProperties`
  record — add new tunables there rather than hardcoding thresholds in the
  strategy class. Both strategies also apply a liquidity gate (`isLiquid`,
  duplicated in each class per the same self-contained-strategy reasoning
  as the DTE/long-put picking logic below) before any delta-based
  selection: a contract needs at least `minOpenInterest` open interest and
  a bid-ask spread no wider than `maxBidAskSpreadRatio` of its mid, or it's
  excluded entirely — otherwise a contract with a perfect delta match but
  no real market (e.g. a 900% wide spread) could get suggested as tradeable
  when it isn't.
- `strategy/bullputspread/` — the second strategy, and the simpler
  reference to read first when adding a third. `BullPutSpreadStrategy` is
  a strict subset of `JadeLizardStrategy` with the call leg removed
  entirely: pick an expiration, pick the short put by target delta, pick
  the long put as the next strike down, require
  `minCreditToWidthRatio × width` (default 0.33 — "collect at least a
  third of the width," the standard vertical-spread guideline, deliberately
  looser than Jade Lizard's 1.0 since there's no naked call premium
  cushioning it). `TradeCandidate`'s call-leg and `upsideBreakEven` fields
  are left null (a vertical spread has only a downside breakeven) — no
  changes to `TradeCandidate` itself were needed, confirming the shared
  shape actually holds up for a strategy with fewer legs.
- `strategy/bearcallspread/` — the third strategy, and the Bull Put
  Spread's mirror image: `BearCallSpreadStrategy` picks an expiration,
  picks the short call by target delta, picks the long call as the next
  strike *up* (the put-side strategies pick the next strike *down* — this
  is the one place the mirroring isn't literally identical code, since a
  credit spread's protective leg always sits further OTM, which is a
  higher strike on the call side and a lower strike on the put side), and
  requires `minCreditToWidthRatio × width` (default 0.33, same guideline
  as Bull Put Spread). Adding this strategy is what surfaced a real gap in
  `TradeCandidate`: the record had `shortPutStrike`/`longPutStrike` but
  only `shortCallStrike` with no `longCallStrike` to hold the protective
  call leg's strike — fine for Jade Lizard's naked short call, not fine
  for a call *spread*. `longCallStrike` was added to `TradeCandidate`
  (right after `shortCallStrike`, mirroring the put pair's ordering),
  which meant updating every direct `new TradeCandidate(...)` call site
  (both strategies, `ScreeningServiceTest`, `ScreeningControllerTest`) —
  worth remembering if `TradeCandidate` grows another field: it's a
  record with a positional constructor, not a builder, so field additions
  ripple to every construction site, not just the strategy adding the
  field.
- `screening/ScreeningService` — the only place that blocks on the reactive
  `EodhdClient`/`MarketDataClient` calls (`.block()`); everything below it
  is synchronous. If this ever needs to be non-blocking end-to-end, that's
  the seam. It's also where the symbol gets normalized (uppercased,
  `.US` suffix stripped) before either client sees it. EODHD's quote is
  treated as optional, not required: it's only ever used for
  `underlyingPrice` (a near-real-time number, nicer than MarketData's own
  24h-delayed `underlyingPrice` embedded in every chain contract, but not
  something the strategy actually needs to function), so a
  `UpstreamApiException` from `EodhdClient.getQuote()` is caught in
  `resolveUnderlyingPrice()`, falls back to the first contract's
  `underlyingPrice`, and is surfaced as a string in `ScreeningResult.warnings()`
  instead of failing the whole request — unlike a `MarketDataClient` failure,
  which is NOT caught and still propagates to `ApiExceptionHandler` as
  before, since there's no candidate to build at all without the chain.
  `ScreeningResult(candidates, warnings)` is what the endpoint actually
  returns (`{"candidates": [...], "warnings": [...]}`), not a bare
  `List<TradeCandidate>`.
- `web/ScreeningController` — thin: one endpoint, `{strategy}/{symbol}` path
  variables map directly onto `ScreeningService.screen(symbol, strategyName)`.
- `web/RootController` — `GET /` returns a small JSON blurb pointing at the
  real endpoint, instead of a 404.

## Commit messages

Use [Conventional Commits](https://www.conventionalcommits.org/) for the
subject line, plus a body explaining the *why*:

```
<type>(<optional scope>): <subject>

<body>
```

- `type` is one of `feat`, `fix`, `refactor`, `test`, `docs`, `chore`, `build`.
- Subject: imperative mood, no trailing period, e.g. `feat(strategy): add short strangle`.
- Body: always include one, even for small changes — explain the motivation
  or reasoning, not a restatement of the diff.

## Adding a new strategy

1. Add a subpackage under `strategy/` (mirror `strategy/bullputspread/` —
   simpler starting point than `strategy/jadelizard/`).
2. Implement `TradeStrategy`, return a unique lowercase-hyphenated `name()`.
3. Annotate it `@Component` — `ScreeningService` picks it up automatically
   and it becomes reachable at `/api/screen/{name}/{symbol}`.
4. If it needs tunable thresholds, add a `@ConfigurationProperties` record
   for it (see `JadeLizardProperties`) and a corresponding block in
   `application.yml`.
