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

This environment does not have Java or Maven on `PATH` by default. They are
installed locally under `~/tools` (Temurin JDK 26, Apache Maven 3.9.16,
downloaded directly from Adoptium/Apache since apt's Ubuntu 22.04 repos don't
carry JDK 26). Export these before running any Maven command:

```bash
export JAVA_HOME=~/tools/jdk-26.0.2+10
export PATH="$JAVA_HOME/bin:~/tools/apache-maven-3.9.16/bin:$PATH"
```

## Commands

```bash
mvn test                                    # run the full test suite
mvn test -Dtest=JadeLizardStrategyTest      # run a single test class
mvn test -Dtest=JadeLizardStrategyTest#picksLegsAndComputesCreditWhenRuleIsSatisfied  # single test method
mvn -DskipTests package                     # build the jar without running tests
mvn spring-boot:run                         # run the service (needs EODHD_API_KEY set)
```

The app needs both API tokens at runtime:

```bash
export EODHD_API_KEY=your-eodhd-api-token
export MARKETDATA_API_KEY=your-marketdata-app-api-token
```

Query it via `GET /api/screen/{strategy}/{symbol}`, e.g.
`curl http://localhost:8080/api/screen/jade-lizard/AAPL` — use the bare
ticker (`AAPL`, not `AAPL.US`).

## Testing

JUnit 5, AssertJ, and Mockito come from `spring-boot-starter-test`.
`org.wiremock:wiremock-standalone` (test scope) mocks both EODHD's and
MarketData.app's HTTP responses in `EodhdClientTest`/`MarketDataClientTest`
so the clients are verified against the real response shapes, not just
hand-built DTOs. `ScreeningControllerTest` uses `@WebMvcTest` (Spring
Boot 4 moved this to `org.springframework.boot.webmvc.test.autoconfigure`
and requires the `spring-boot-starter-webmvc-test` test dependency) with
`@MockitoBean` from `spring-test` — Boot 4 removed the older `@MockBean`.

**Coverage:** `jacoco-maven-plugin` runs on every `mvn test` (bound to the
`test` phase, not `verify`) and enforces a minimum of 80% overall line
coverage (`BUNDLE`/`LINE`/`COVEREDRATIO`) — the build fails if coverage
drops below that. HTML report: `target/site/jacoco/index.html`.

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
  `.US` suffix stripped) before either client sees it.
- `web/ScreeningController` — thin: one endpoint, `{strategy}/{symbol}` path
  variables map directly onto `ScreeningService.screen(symbol, strategyName)`.

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
