# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Spring Boot service that pulls a stock quote from [EODHD](https://eodhd.com/)
and its option chain from [MarketData.app](https://www.marketdata.app/), then
screens them for options-selling trade candidates. It currently implements
one strategy — the **Jade Lizard** (short call + short put vertical spread)
— built behind a `TradeStrategy` interface so more strategies can be added
without touching existing code.

**Why two data sources:** EODHD's options chain endpoint
(`/api/mp/unicornbay/options/eod`) is a paid marketplace add-on not included
on the free plan; MarketData.app has a permanent free tier ("Free Forever":
100 requests/day, no card) that includes real option chains with Greeks —
just 24h delayed. EODHD's quote endpoint stayed since it already worked on
the free plan. **Symbol format matters**: MarketData.app rejects
exchange-suffixed symbols like `AAPL.US` outright (`"Symbol not found"`)
while EODHD accepts the bare ticker fine — `ScreeningService` normalizes to
the bare uppercased ticker before calling either client.

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
  range (~1-75 DTE by default, wide enough for near/medium-term strategies)
  and maps the response into `OptionContract`. The wire format is
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
  null rather than the shape being subclassed per-strategy.
- `strategy/jadelizard/` — the first strategy. `JadeLizardStrategy` picks an
  expiration inside the configured DTE window closest to the target DTE,
  picks the short call/put whose delta is closest to the configured target
  delta, picks the long put as the next strike down from the short put, and
  only returns a candidate when credit received is at least
  `minCreditToWidthRatio × putSpreadWidth` (the standard Jade Lizard
  construction rule, configured in `application.yml` under
  `strategy.jade-lizard`). `JadeLizardProperties` is a `@ConfigurationProperties`
  record — add new tunables there rather than hardcoding thresholds in the
  strategy class.
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

1. Add a subpackage under `strategy/` (mirror `strategy/jadelizard/`).
2. Implement `TradeStrategy`, return a unique lowercase-hyphenated `name()`.
3. Annotate it `@Component` — `ScreeningService` picks it up automatically
   and it becomes reachable at `/api/screen/{name}/{symbol}`.
4. If it needs tunable thresholds, add a `@ConfigurationProperties` record
   for it (see `JadeLizardProperties`) and a corresponding block in
   `application.yml`.
