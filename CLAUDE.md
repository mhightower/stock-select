# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Package-specific rationale (MarketData.app quota/DTE tuning, health-check
design, strategy internals, ScreeningService fallback logic) lives in
nested `CLAUDE.md` files under `src/main/java/com/stockselect/` and loads
automatically when you're working in that directory — this file stays
scoped to what applies project-wide.

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

**Why two data sources:** EODHD's options chain endpoint is a paid
marketplace add-on not included on the free plan; MarketData.app has a
free tier that includes real option chains with Greeks (24h delayed).
EODHD's quote endpoint stayed since it already worked on the free plan.
**Symbol format matters**: MarketData.app rejects exchange-suffixed
symbols like `AAPL.US` outright while EODHD accepts the bare ticker fine —
`ScreeningService` normalizes to the bare uppercased ticker before calling
either client.

**MarketData.app's free-tier quota is metered per contract returned, not
per HTTP call** — in practice this leaves only ~10 chain screens/day, not
the 100-10,000/day the docs/headers suggest. Budget test batches
accordingly and check `x-api-ratelimit-remaining` before running more than
a couple of symbols through `/api/screen/*` in one session. See
`marketdata/CLAUDE.md` for the full investigation and the mitigation
(narrowed DTE fetch window).

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
(`ScreeningResult`), not a bare array — see `screening/CLAUDE.md` for the
EODHD degradation note.

`GET /health` reports whether each vendor is reachable — see
`health/CLAUDE.md` for why it doesn't make its own vendor calls.

## Testing

JUnit 5, AssertJ, and Mockito come from `spring-boot-starter-test`.
`org.wiremock:wiremock-standalone` (test scope) mocks both EODHD's and
MarketData.app's HTTP responses in `EodhdClientTest`/`MarketDataClientTest`
so the clients are verified against the real response shapes, not just
hand-built DTOs. `ScreeningControllerTest` uses `@WebMvcTest` (Spring
Boot 4 moved this to `org.springframework.boot.webmvc.test.autoconfigure`
and requires the `spring-boot-starter-webmvc-test` test dependency) with
`@MockitoBean` from `spring-test` — Boot 4 removed the older `@MockBean`.
`StockSelectApplicationTests` is the one full `@SpringBootTest` (not a
slice) — it boots the entire context on a random port and hits `/` and
`/health` for real, catching bean-wiring/`@ConfigurationProperties`-binding
failures that no slice test or pure unit test would surface (everything
else only loads part of the context or none at all). It needs no API keys
or network access, since neither endpoint calls a vendor and both vendor
properties have safe defaults (`${EODHD_API_KEY:demo}` /
`${MARKETDATA_API_KEY:}`). Needs three extra test-scope dependencies
found the hard way: Spring Boot 4 moved `TestRestTemplate` out of
`spring-boot-starter-test` entirely into `spring-boot-resttestclient`
(package `org.springframework.boot.resttestclient`, not the old
`org.springframework.boot.test.web.client`); that module's
autoconfiguration doesn't activate automatically even with
`webEnvironment = RANDOM_PORT` — it needs the test class annotated with
`@AutoConfigureTestRestTemplate`; and its autoconfiguration reflectively
needs `RestTemplateBuilder`, which lives in `spring-boot-restclient` — a
module `spring-boot-starter-web` doesn't pull in since this app only ever
uses `WebClient`, never `RestTemplate`, in its own code.

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
(see `marketdata/CLAUDE.md`) were both found this way, not by unit tests.
Each is gated behind `@EnabledIfEnvironmentVariable` on its API key and
skips cleanly (not fails) when that key isn't set, so `./mvnw verify` is
safe to run without credentials — it just skips the ITs. To actually
exercise them: `source .env` (`set -a`/`set +a`) then `./mvnw verify`.
Follow the same `*ClientIT` pattern for any new vendor client.

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
- `marketdata/` — talking to MarketData.app for the option chain. See
  `marketdata/CLAUDE.md` for the wire format, DTE-window/quota rationale,
  timezone handling, and the buffer-size gotcha.
- `health/` — vendor health check (`GET /health`). See `health/CLAUDE.md`
  for why it never makes its own vendor calls.
- Root package (`com.stockselect`) — `UpstreamApiException` wraps any
  `WebClientResponseException` from either vendor client with which vendor
  it came from; `EodhdClient`/`MarketDataClient` map to it via
  `.onErrorMap(WebClientResponseException.class, ...)` right after
  `bodyToMono`. `web/ApiExceptionHandler` (`@RestControllerAdvice`)
  translates it into a clean JSON `{"error": "..."}` body: 429 from a
  vendor stays 429, 401/403 (bad key/entitlement) becomes 502, anything
  else also 502. Without this, a vendor error surfaced as a raw 500 with a
  full stack trace in the response. It also catches
  `NoHandlerFoundException` for the same reason — any unmapped path
  (including `/`, before `RootController` existed) otherwise fell through
  to Spring Boot's Whitelabel HTML error page. That requires
  `spring.mvc.throw-exception-if-no-handler-found: true` and
  `spring.web.resources.add-mappings: false` in `application.yml`, or
  DispatcherServlet swallows the 404 instead of throwing it.
- `strategy/` — the extension point. See `strategy/CLAUDE.md` for
  `TradeStrategy`/`TradeCandidate` conventions, per-strategy notes, and
  the steps for adding a new strategy.
- `screening/ScreeningService` — see `screening/CLAUDE.md` for the
  blocking seam and the EODHD-optional/MarketData-required fallback logic.
- `web/ScreeningController` — thin: one endpoint, `{strategy}/{symbol}`
  path variables map directly onto `ScreeningService.screen(symbol, strategyName)`.
- `web/RootController` — `GET /` returns a small JSON blurb pointing at the
  real endpoint, instead of a 404.

## Observability

`GET /health` stays at the root path (see `health/CLAUDE.md` for why);
the rest of the ops surface lives under `/actuator`:

```bash
curl http://localhost:8080/actuator/prometheus   # Prometheus scrape format
curl http://localhost:8080/actuator/metrics      # JSON list of available metric names
curl http://localhost:8080/actuator/info         # build/app info
```

`management.endpoints.web.exposure.include` in `application.yml` is an
explicit allowlist (`health, prometheus, metrics, info`), not `*`.

**Business metrics** (`stockselect.*` namespace):

| Metric | Type | Tags |
|---|---|---|
| `stockselect.screen.requests` | Counter | `strategy`, `outcome` (`success`/`failure`) |
| `stockselect.screen.latency` | Timer | `strategy`, `outcome` |
| `stockselect.vendor.calls` | Counter | `vendor` (`EODHD`/`MarketData.app`), `outcome` |
| `stockselect.vendor.ratelimit.remaining` | Gauge | `vendor` |

`strategy` is never the raw user-supplied path segment — an unknown
strategy name is tagged `strategy=unknown` instead of echoing arbitrary
input, to keep the Prometheus label cardinality bounded.

**Structured logging:** `ScreeningService` logs one line per completed
request (`strategy`, `symbol`, `status`, `latencyMs`); `EodhdClient`/
`MarketDataClient` each log one line per vendor call (`vendor`, `status`,
`latencyMs`). Fields are attached via SLF4J's fluent `addKeyValue` API,
not MDC — MDC doesn't reliably propagate across this app's
virtual-thread-per-request executor (`ScreeningService`) or the vendor
clients' Reactor/Netty callback threads where these calls happen.
`logging.pattern.console` in `application.yml` renders them via
Logback's `%kvp` token.

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
