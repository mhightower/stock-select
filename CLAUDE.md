# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Spring Boot service that pulls a stock quote and its option chain from the
[EODHD](https://eodhd.com/) API and screens them for options-selling trade
candidates. It currently implements one strategy — the **Jade Lizard**
(short call + short put vertical spread) — built behind a `TradeStrategy`
interface so more strategies can be added without touching existing code.

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

The app needs an EODHD API token at runtime:

```bash
export EODHD_API_KEY=your-eodhd-api-token
```

Query it via `GET /api/screen/{strategy}/{symbol}`, e.g.
`curl http://localhost:8080/api/screen/jade-lizard/AAPL`.

## Architecture

**Data flow:** `ScreeningController` → `ScreeningService` → `EodhdClient`
(fetches quote + full option chain) → the matching `TradeStrategy` bean
(picks legs, prices them, returns `TradeCandidate`s).

- `eodhd/` — everything about talking to EODHD. `EodhdClient` wraps a
  `WebClient` and exposes `getQuote(symbol)` (flat JSON from
  `/api/real-time/{symbol}`) and `getOptionsChain(symbol)` (JSON:API-style
  envelope from `/api/mp/unicornbay/options/eod`, which requires a
  unicornbay options data subscription on the EODHD account — the `demo`
  token will not work for this endpoint). `OptionsResponse` models the
  `{meta, data: [{attributes: {...}}]}` envelope; `EodhdClient` unwraps it
  to a flat `List<OptionContract>` so nothing downstream deals with the
  envelope.
- `strategy/` — the extension point. `TradeStrategy` is the interface every
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
  `EodhdClient` calls (`.block()`); everything below it is synchronous. If
  this ever needs to be non-blocking end-to-end, that's the seam.
- `web/ScreeningController` — thin: one endpoint, `{strategy}/{symbol}` path
  variables map directly onto `ScreeningService.screen(symbol, strategyName)`.

## Adding a new strategy

1. Add a subpackage under `strategy/` (mirror `strategy/jadelizard/`).
2. Implement `TradeStrategy`, return a unique lowercase-hyphenated `name()`.
3. Annotate it `@Component` — `ScreeningService` picks it up automatically
   and it becomes reachable at `/api/screen/{name}/{symbol}`.
4. If it needs tunable thresholds, add a `@ConfigurationProperties` record
   for it (see `JadeLizardProperties`) and a corresponding block in
   `application.yml`.
