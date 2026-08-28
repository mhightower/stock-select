# Observability & Metrics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Prometheus metrics export via Micrometer, business-level metrics for the screening flow and vendor calls, and structured log fields for key request metadata, without adding new frameworks or config classes.

**Architecture:** `MeterRegistry` (already auto-configured transitively via `spring-boot-starter-actuator`) is constructor-injected into `ScreeningService`, `EodhdClient`, and `MarketDataClient` — the same DI pattern those classes already use for `VendorHealthTracker`. Metrics are recorded inline at each class's existing success/failure boundaries. Structured log fields use SLF4J's fluent `addKeyValue` API (not MDC, which doesn't propagate across this app's virtual-thread and Reactor/Netty boundaries) rendered via Logback's `%kvp` pattern token.

**Tech Stack:** Spring Boot 4.1.1 Actuator, Micrometer 1.17.1 (already on the classpath), `micrometer-registry-prometheus` (new dependency), Logback 1.5.38 / SLF4J 2.0.18 (already on the classpath, no logging framework change).

**Spec:** `docs/superpowers/specs/2026-08-28-observability-metrics-design.md`

## Global Constraints

- Use `./mvnw`, not a system `mvn` (see root `CLAUDE.md`).
- Run the full `./mvnw test` (not just the targeted test class) before every commit — the project's 80% JaCoCo line-coverage gate runs on `./mvnw test` and must stay green.
- No new `@Configuration` class — `PrometheusMeterRegistry` autoconfigures once the dependency is on the classpath.
- No auth added to the new endpoints — the app has no Spring Security dependency today.
- No JSON logging / `logstash-logback-encoder` — text logs with `%kvp` only.
- No percentile histograms on the timers — count/sum only.
- The `strategy` Prometheus tag must never echo raw user input directly — unknown strategies use a fixed `strategy=unknown` sentinel (cardinality guard; see spec).
- `/health` (path, response shape, behavior) must not change.
- Follow [Conventional Commits](https://www.conventionalcommits.org/) for every commit message, with a body explaining why (see root `CLAUDE.md`).

---

## Task 1: Prometheus export and endpoint exposure

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/stockselect/StockSelectApplicationTests.java`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: `/actuator/prometheus` and `/actuator/metrics` become reachable endpoints; `spring.application.name` becomes `stock-select` (used as a common metric tag). Later tasks don't depend on anything here beyond the endpoints existing.

- [ ] **Step 1: Write the failing tests**

Add these two tests to `src/test/java/com/stockselect/StockSelectApplicationTests.java`, right after `servesTheHealthEndpointWithoutMakingAnyVendorCalls`:

```java
    @Test
    void servesPrometheusMetricsUnderActuator() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("jvm_memory_used_bytes");
    }

    @Test
    void servesMetricsListUnderActuator() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/metrics", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"names\"");
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -Dtest=StockSelectApplicationTests`
Expected: FAIL — both new tests get `404 Not Found` (the endpoints aren't exposed yet and `micrometer-registry-prometheus` isn't on the classpath).

- [ ] **Step 3: Add the Prometheus registry dependency**

In `pom.xml`, add this dependency immediately after the `spring-boot-starter-actuator` dependency block:

```xml
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-registry-prometheus</artifactId>
        </dependency>
```

- [ ] **Step 4: Update `application.yml`**

Replace the `spring:` block and the `management:` block with:

```yaml
spring:
  application:
    name: stock-select
  threads:
    virtual:
      enabled: true
  web:
    resources:
      add-mappings: false

management:
  endpoints:
    web:
      # Exposes health at /health instead of the default /actuator/health; the rest of the
      # ops surface (metrics, prometheus, info) stays under /actuator to keep it separate
      # from both /health and the app's own /api/* routes.
      base-path: /
      exposure:
        include: health, prometheus, metrics, info
      path-mapping:
        prometheus: actuator/prometheus
        metrics: actuator/metrics
        info: actuator/info
  endpoint:
    health:
      show-details: always
  health:
    diskspace:
      enabled: false
  metrics:
    tags:
      application: ${spring.application.name}
```

Also replace the final `logging:` block (at the bottom of the file) with:

```yaml
logging:
  level:
    '[com.stockselect]': INFO
  pattern:
    # %kvp renders the SLF4J fluent addKeyValue() fields added in later tasks (strategy,
    # symbol, vendor, status, latencyMs) — silently dropped by Boot's default colored
    # pattern otherwise. ANSI color codes are dropped in the process, which is fine for
    # containerized log output that usually gets stripped/reformatted by a log collector.
    console: "%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %5p [%15.15t] %-40.40logger{39} : %m %kvp%n"
```

Every other key in `application.yml` (`server`, `eodhd`, `marketdata`, `strategy.*`) is unchanged.

- [ ] **Step 5: Run tests to verify they pass**

Run: `./mvnw test -Dtest=StockSelectApplicationTests`
Expected: PASS — all 4 tests in the class (2 existing + 2 new) pass.

- [ ] **Step 6: Run the full suite**

Run: `./mvnw test`
Expected: PASS, coverage gate holds.

- [ ] **Step 7: Commit**

```bash
git add pom.xml src/main/resources/application.yml src/test/java/com/stockselect/StockSelectApplicationTests.java
git commit -m "$(cat <<'EOF'
feat(observability): add Prometheus metrics export

Adds micrometer-registry-prometheus (the only dependency needed —
MeterRegistry itself already comes transitively via
spring-boot-starter-actuator) and exposes /actuator/prometheus,
/actuator/metrics, and /actuator/info without moving /health, which
stays at the root path it already had. exposure.include is an
explicit allowlist rather than "*". Also adds spring.application.name
as a common metric tag, and switches the console log pattern to a
plain (non-ANSI) one ending in %kvp so the structured key-value log
fields added in later tasks actually render.
EOF
)"
```

---

## Task 2: Screen-request metrics and structured logging in ScreeningService

**Files:**
- Modify: `src/main/java/com/stockselect/screening/ScreeningService.java`
- Modify: `src/test/java/com/stockselect/screening/ScreeningServiceTest.java`

**Interfaces:**
- Consumes: `io.micrometer.core.instrument.MeterRegistry` (interface, from `micrometer-core`, already on the classpath).
- Produces: `ScreeningService`'s constructor becomes `ScreeningService(EodhdClient eodhdClient, MarketDataClient marketDataClient, List<TradeStrategy> strategies, MeterRegistry meterRegistry)`. Metrics: `stockselect.screen.requests` (Counter, tags `strategy`, `outcome`), `stockselect.screen.latency` (Timer, tags `strategy`, `outcome`). No other task depends on this signature.

- [ ] **Step 1: Write the failing tests**

In `src/test/java/com/stockselect/screening/ScreeningServiceTest.java`, add these imports (alongside the existing ones):

```java
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.stream.Collectors;
```

Add this field, alongside the existing `@Mock` fields:

```java
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
```

Update the 3 existing `new ScreeningService(eodhdClient, marketDataClient, List.of(new StubStrategy("jade-lizard")))` call sites (in `dispatchesToTheMatchingStrategyWithABareUppercasedSymbol`, `throwsForAnUnknownStrategyName`, and `fallsBackToMarketDataPriceAndWarnsWhenEodhdIsUnavailable`) to pass `meterRegistry` as a 4th argument:

```java
        ScreeningService service = new ScreeningService(eodhdClient, marketDataClient, List.of(new StubStrategy("jade-lizard")), meterRegistry);
```

Add these 4 new tests at the end of the class, right before the `StubStrategy` record:

```java
    @Test
    void recordsScreenRequestMetricsOnSuccess() {
        Quote quote = new Quote("AAPL.US", 0L, 190, 195, 189, 193.5, 1_000_000, 191, 2.5, 1.31);
        when(eodhdClient.getQuote("AAPL")).thenReturn(Mono.just(quote));
        when(marketDataClient.getOptionsChain("AAPL")).thenReturn(Flux.empty());
        ScreeningService service = new ScreeningService(eodhdClient, marketDataClient, List.of(new StubStrategy("jade-lizard")), meterRegistry);

        service.screen("AAPL", "jade-lizard");

        assertThat(meterRegistry.counter("stockselect.screen.requests", "strategy", "jade-lizard", "outcome", "success").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.timer("stockselect.screen.latency", "strategy", "jade-lizard", "outcome", "success").count())
                .isEqualTo(1L);
    }

    @Test
    void recordsAFailureMetricWithAnUnknownStrategySentinelForAnUnknownStrategyName() {
        ScreeningService service = new ScreeningService(eodhdClient, marketDataClient, List.of(new StubStrategy("jade-lizard")), meterRegistry);

        assertThatThrownBy(() -> service.screen("AAPL", "iron-condor"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(meterRegistry.counter("stockselect.screen.requests", "strategy", "unknown", "outcome", "failure").count())
                .isEqualTo(1.0);
    }

    @Test
    void recordsAFailureMetricWhenMarketDataFails() {
        when(eodhdClient.getQuote("AAPL")).thenReturn(Mono.just(
                new Quote("AAPL.US", 0L, 190, 195, 189, 193.5, 1_000_000, 191, 2.5, 1.31)));
        when(marketDataClient.getOptionsChain("AAPL")).thenReturn(Flux.error(
                new UpstreamApiException("MarketData.app", HttpStatus.TOO_MANY_REQUESTS, new RuntimeException("429"))));
        ScreeningService service = new ScreeningService(eodhdClient, marketDataClient, List.of(new StubStrategy("jade-lizard")), meterRegistry);

        assertThatThrownBy(() -> service.screen("AAPL", "jade-lizard"))
                .isInstanceOf(UpstreamApiException.class);

        assertThat(meterRegistry.counter("stockselect.screen.requests", "strategy", "jade-lizard", "outcome", "failure").count())
                .isEqualTo(1.0);
    }

    @Test
    void logsStructuredFieldsOnScreenCompletion() {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(ScreeningService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
        try {
            when(eodhdClient.getQuote("AAPL")).thenReturn(Mono.just(
                    new Quote("AAPL.US", 0L, 190, 195, 189, 193.5, 1_000_000, 191, 2.5, 1.31)));
            when(marketDataClient.getOptionsChain("AAPL")).thenReturn(Flux.empty());
            ScreeningService service = new ScreeningService(eodhdClient, marketDataClient, List.of(new StubStrategy("jade-lizard")), meterRegistry);

            service.screen("AAPL", "jade-lizard");

            ILoggingEvent event = appender.list.get(appender.list.size() - 1);
            Map<String, String> fields = event.getKeyValuePairs().stream()
                    .collect(Collectors.toMap(kv -> kv.key, kv -> String.valueOf(kv.value)));
            assertThat(fields).containsEntry("strategy", "jade-lizard");
            assertThat(fields).containsEntry("symbol", "AAPL");
            assertThat(fields).containsEntry("status", "success");
            assertThat(fields).containsKey("latencyMs");
        } finally {
            logbackLogger.detachAppender(appender);
        }
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -Dtest=ScreeningServiceTest`
Expected: FAIL to compile — `ScreeningService` has no 4-arg constructor yet.

- [ ] **Step 3: Implement**

Replace the full contents of `src/main/java/com/stockselect/screening/ScreeningService.java` with:

```java
package com.stockselect.screening;

import com.stockselect.UpstreamApiException;
import com.stockselect.eodhd.EodhdClient;
import com.stockselect.eodhd.dto.Quote;
import com.stockselect.marketdata.MarketDataClient;
import com.stockselect.strategy.OptionContract;
import com.stockselect.strategy.StrategyContext;
import com.stockselect.strategy.TradeStrategy;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ScreeningService {

    private static final Logger log = LoggerFactory.getLogger(ScreeningService.class);

    private final EodhdClient eodhdClient;
    private final MarketDataClient marketDataClient;
    private final Map<String, TradeStrategy> strategiesByName;
    private final MeterRegistry meterRegistry;

    public ScreeningService(EodhdClient eodhdClient, MarketDataClient marketDataClient, List<TradeStrategy> strategies,
            MeterRegistry meterRegistry) {
        this.eodhdClient = eodhdClient;
        this.marketDataClient = marketDataClient;
        this.strategiesByName = strategies.stream()
                .collect(Collectors.toMap(strategy -> strategy.name(), Function.identity()));
        this.meterRegistry = meterRegistry;
    }

    public ScreeningResult screen(String symbol, String strategyName) {
        TradeStrategy strategy = strategiesByName.get(strategyName);
        if (strategy == null) {
            // "unknown" sentinel, never the raw strategyName — an arbitrary user-supplied path
            // segment as a Prometheus tag value would be unbounded-cardinality label growth.
            meterRegistry.counter("stockselect.screen.requests", "strategy", "unknown", "outcome", "failure").increment();
            throw new IllegalArgumentException("Unknown strategy: " + strategyName
                    + ". Available: " + strategiesByName.keySet());
        }

        long startNanos = System.nanoTime();
        String outcome = "success";
        try {
            // MarketData.app rejects exchange-suffixed symbols (e.g. "AAPL.US") outright; EODHD
            // accepts the bare ticker fine, so normalize to bare for both clients.
            String normalizedSymbol = symbol.toUpperCase().replaceFirst("\\.US$", "");

            List<OptionContract> optionsChain;
            List<String> warnings = new ArrayList<>();
            double underlyingPrice;
            // The chain and quote calls are independent, so fetch them concurrently on their own
            // virtual threads instead of serially — halves the vendor latency on the happy path.
            try (var vthreads = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<List<OptionContract>> chainFuture =
                        vthreads.submit(() -> marketDataClient.getOptionsChain(normalizedSymbol).collectList().block());
                Future<Quote> quoteFuture = vthreads.submit(() -> eodhdClient.getQuote(normalizedSymbol).block());

                optionsChain = unwrap(chainFuture);
                underlyingPrice = resolveUnderlyingPrice(quoteFuture, optionsChain, warnings);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while screening " + normalizedSymbol, e);
            }

            StrategyContext context = new StrategyContext(normalizedSymbol, underlyingPrice, optionsChain);
            return new ScreeningResult(strategy.evaluate(context), warnings);
        } catch (RuntimeException ex) {
            outcome = "failure";
            throw ex;
        } finally {
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
            meterRegistry.counter("stockselect.screen.requests", "strategy", strategyName, "outcome", outcome).increment();
            Timer.builder("stockselect.screen.latency")
                    .tag("strategy", strategyName)
                    .tag("outcome", outcome)
                    .register(meterRegistry)
                    .record(elapsed);
            log.atInfo()
                    .addKeyValue("strategy", strategyName)
                    .addKeyValue("symbol", symbol)
                    .addKeyValue("status", outcome)
                    .addKeyValue("latencyMs", elapsed.toMillis())
                    .log("screen completed");
        }
    }

    /**
     * EODHD's quote is only a "nicer" (near-real-time vs. MarketData's 24h-delayed) price than
     * what's already embedded in the option chain, not something the strategy strictly needs —
     * so a vendor failure here degrades to a warning instead of failing the whole request.
     */
    private double resolveUnderlyingPrice(Future<Quote> quoteFuture, List<OptionContract> optionsChain,
            List<String> warnings) throws InterruptedException {
        try {
            return unwrap(quoteFuture).close();
        } catch (UpstreamApiException ex) {
            warnings.add("EODHD unavailable (" + ex.getMessage() + "); using MarketData.app's price instead.");
            return optionsChain.isEmpty() ? 0.0 : optionsChain.get(0).underlyingPrice();
        }
    }

    /** Unwraps a {@link Future}'s {@link ExecutionException} so the original vendor exception's
     * type is preserved for {@code ApiExceptionHandler} to match on. */
    private static <T> T unwrap(Future<T> future) throws InterruptedException {
        try {
            return future.get();
        } catch (ExecutionException e) {
            switch (e.getCause()) {
                case RuntimeException runtimeException -> throw runtimeException;
                case Error error -> throw error;
                default -> throw new IllegalStateException(e.getCause());
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -Dtest=ScreeningServiceTest`
Expected: PASS — all 7 tests (3 existing + 4 new).

- [ ] **Step 5: Run the full suite**

Run: `./mvnw test`
Expected: PASS, coverage gate holds. (`ScreeningControllerTest` mocks `ScreeningService` via `@MockitoBean` and never constructs it directly, so it needs no change.)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/stockselect/screening/ScreeningService.java src/test/java/com/stockselect/screening/ScreeningServiceTest.java
git commit -m "$(cat <<'EOF'
feat(observability): add screen-request metrics and structured logging

ScreeningService now records stockselect.screen.requests (Counter,
tags strategy+outcome) and stockselect.screen.latency (Timer, same
tags) around the whole screen() call, and logs one structured line
per completed request (strategy, symbol, status, latencyMs) via
SLF4J's fluent addKeyValue API rather than MDC — MDC doesn't reliably
propagate across the virtual-thread executor this method already
uses. An unknown strategy name is tagged as strategy=unknown rather
than echoing the user-supplied path segment, to keep the Prometheus
label cardinality bounded.
EOF
)"
```

---

## Task 3: Vendor-call metrics and structured logging in EodhdClient

**Files:**
- Modify: `src/main/java/com/stockselect/eodhd/EodhdClient.java`
- Modify: `src/test/java/com/stockselect/eodhd/EodhdClientTest.java`

**Interfaces:**
- Consumes: `io.micrometer.core.instrument.MeterRegistry`.
- Produces: `EodhdClient`'s constructor becomes `EodhdClient(WebClient eodhdWebClient, EodhdProperties properties, VendorHealthTracker healthTracker, MeterRegistry meterRegistry)`. Metric: `stockselect.vendor.calls` (Counter, tags `vendor`, `outcome`).

- [ ] **Step 1: Write the failing tests**

In `src/test/java/com/stockselect/eodhd/EodhdClientTest.java`, add these imports:

```java
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.stream.Collectors;
```

Add this field, alongside the existing `healthTracker` field:

```java
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
```

Update the `client()` helper to pass it through:

```java
    private EodhdClient client() {
        EodhdProperties properties = new EodhdProperties(wireMock.baseUrl(), "test-token");
        WebClient webClient = WebClient.builder().baseUrl(properties.baseUrl()).build();
        return new EodhdClient(webClient, properties, healthTracker, meterRegistry);
    }
```

Update the direct construction inside `mapsASlowVendorResponseToAGatewayTimeoutUpstreamExceptionAndRecordsFailure`:

```java
        EodhdClient client = new EodhdClient(webClient, properties, healthTracker, meterRegistry);
```

Add a metric assertion at the end of `parsesQuoteResponseAndRecordsSuccess` (right after the existing `assertThat(healthTracker.outcome("EODHD"))...` line):

```java
        assertThat(meterRegistry.counter("stockselect.vendor.calls", "vendor", "EODHD", "outcome", "success").count())
                .isEqualTo(1.0);
```

Add a metric assertion at the end of `wrapsAVendorErrorResponseInAnUpstreamApiExceptionAndRecordsFailure` (right after its existing `assertThat(healthTracker.outcome("EODHD"))...` line):

```java
        assertThat(meterRegistry.counter("stockselect.vendor.calls", "vendor", "EODHD", "outcome", "failure").count())
                .isEqualTo(1.0);
```

Add a metric assertion at the end of `mapsASlowVendorResponseToAGatewayTimeoutUpstreamExceptionAndRecordsFailure` (right after its existing `assertThat(healthTracker.outcome("EODHD"))...` line):

```java
        assertThat(meterRegistry.counter("stockselect.vendor.calls", "vendor", "EODHD", "outcome", "failure").count())
                .isEqualTo(1.0);
```

Add a new test at the end of the class, before the closing brace:

```java
    @Test
    void logsStructuredFieldsForEachVendorCall() {
        wireMock.stubFor(get(urlPathEqualTo("/api/real-time/AAPL"))
                .willReturn(okJson("""
                        {
                          "code": "AAPL.US",
                          "timestamp": 1717000000,
                          "open": 190.1,
                          "high": 195.2,
                          "low": 189.0,
                          "close": 193.5,
                          "volume": 1000000,
                          "previousClose": 191.0,
                          "change": 2.5,
                          "change_p": 1.31
                        }
                        """)));

        Logger logbackLogger = (Logger) LoggerFactory.getLogger(EodhdClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
        try {
            client().getQuote("AAPL").block();

            ILoggingEvent event = appender.list.get(appender.list.size() - 1);
            Map<String, String> fields = event.getKeyValuePairs().stream()
                    .collect(Collectors.toMap(kv -> kv.key, kv -> String.valueOf(kv.value)));
            assertThat(fields).containsEntry("vendor", "EODHD");
            assertThat(fields).containsEntry("status", "success");
            assertThat(fields).containsKey("latencyMs");
        } finally {
            logbackLogger.detachAppender(appender);
        }
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -Dtest=EodhdClientTest`
Expected: FAIL to compile — `EodhdClient` has no 4-arg constructor yet.

- [ ] **Step 3: Implement**

Replace the full contents of `src/main/java/com/stockselect/eodhd/EodhdClient.java` with:

```java
package com.stockselect.eodhd;

import com.stockselect.UpstreamApiException;
import com.stockselect.config.EodhdProperties;
import com.stockselect.eodhd.dto.Quote;
import com.stockselect.health.VendorHealthTracker;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
public class EodhdClient {

    private static final Logger log = LoggerFactory.getLogger(EodhdClient.class);

    public static final String VENDOR = "EODHD";

    private final WebClient webClient;
    private final EodhdProperties properties;
    private final VendorHealthTracker healthTracker;
    private final MeterRegistry meterRegistry;

    public EodhdClient(WebClient eodhdWebClient, EodhdProperties properties, VendorHealthTracker healthTracker,
            MeterRegistry meterRegistry) {
        this.webClient = eodhdWebClient;
        this.properties = properties;
        this.healthTracker = healthTracker;
        this.meterRegistry = meterRegistry;
    }

    public Mono<Quote> getQuote(String symbol) {
        long startNanos = System.nanoTime();
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/real-time/{symbol}")
                        .queryParam("api_token", properties.apiKey())
                        .queryParam("fmt", "json")
                        .build(symbol))
                .retrieve()
                .bodyToMono(Quote.class)
                .doOnSuccess(quote -> {
                    healthTracker.recordSuccess(VENDOR);
                    recordVendorCall("success", startNanos);
                })
                .onErrorMap(WebClientResponseException.class,
                        ex -> new UpstreamApiException(VENDOR, ex.getStatusCode(), ex))
                .onErrorMap(WebClientRequestException.class,
                        ex -> new UpstreamApiException(VENDOR, HttpStatus.GATEWAY_TIMEOUT, ex))
                .doOnError(UpstreamApiException.class, ex -> {
                    healthTracker.recordFailure(VENDOR, ex.getMessage());
                    recordVendorCall("failure", startNanos);
                });
    }

    private void recordVendorCall(String outcome, long startNanos) {
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
        meterRegistry.counter("stockselect.vendor.calls", "vendor", VENDOR, "outcome", outcome).increment();
        log.atInfo()
                .addKeyValue("vendor", VENDOR)
                .addKeyValue("status", outcome)
                .addKeyValue("latencyMs", elapsed.toMillis())
                .log("vendor call completed");
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -Dtest=EodhdClientTest`
Expected: PASS — all 4 tests (3 existing + 1 new).

- [ ] **Step 5: Run the full suite**

Run: `./mvnw test`
Expected: PASS, coverage gate holds.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/stockselect/eodhd/EodhdClient.java src/test/java/com/stockselect/eodhd/EodhdClientTest.java
git commit -m "$(cat <<'EOF'
feat(observability): add vendor-call metrics and structured logging to EodhdClient

Records stockselect.vendor.calls (Counter, tags vendor+outcome) and
logs one structured line per call (vendor, status, latencyMs) right
next to the existing VendorHealthTracker success/failure recording —
same scope (only UpstreamApiException after error mapping), same
place in the reactive chain.
EOF
)"
```

---

## Task 4: Vendor-call metrics, rate-limit gauge, and structured logging in MarketDataClient

**Files:**
- Modify: `src/main/java/com/stockselect/marketdata/MarketDataClient.java`
- Modify: `src/test/java/com/stockselect/marketdata/MarketDataClientTest.java`

**Interfaces:**
- Consumes: `io.micrometer.core.instrument.MeterRegistry`.
- Produces: `MarketDataClient`'s constructor becomes `MarketDataClient(WebClient marketDataWebClient, VendorHealthTracker healthTracker, MeterRegistry meterRegistry)`. Metrics: `stockselect.vendor.calls` (Counter, tags `vendor`, `outcome`), `stockselect.vendor.ratelimit.remaining` (Gauge, tag `vendor`).

- [ ] **Step 1: Write the failing tests**

In `src/test/java/com/stockselect/marketdata/MarketDataClientTest.java`, add these imports:

```java
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.stream.Collectors;
```

Add this field, alongside the existing `healthTracker` field:

```java
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
```

Update the `client()` helper:

```java
    private MarketDataClient client() {
        WebClient webClient = WebClient.builder()
                .baseUrl(wireMock.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .build();
        return new MarketDataClient(webClient, healthTracker, meterRegistry);
    }
```

Update the direct construction inside `mapsASlowVendorResponseToAGatewayTimeoutUpstreamExceptionAndRecordsFailure`:

```java
        MarketDataClient client = new MarketDataClient(webClient, healthTracker, meterRegistry);
```

Add a metric assertion at the end of `parsesTheParallelArrayChainResponse` (right after the existing `assertThat(healthTracker.outcome("MarketData.app"))...` line):

```java
        assertThat(meterRegistry.counter("stockselect.vendor.calls", "vendor", "MarketData.app", "outcome", "success").count())
                .isEqualTo(1.0);
```

Add a metric assertion at the end of `wrapsAVendorErrorResponseInAnUpstreamApiExceptionAndRecordsFailure` (right after its existing `assertThat(healthTracker.outcome("MarketData.app"))...` line):

```java
        assertThat(meterRegistry.counter("stockselect.vendor.calls", "vendor", "MarketData.app", "outcome", "failure").count())
                .isEqualTo(1.0);
```

Add a metric assertion at the end of `mapsASlowVendorResponseToAGatewayTimeoutUpstreamExceptionAndRecordsFailure` (right after its existing `assertThat(healthTracker.outcome("MarketData.app"))...` line):

```java
        assertThat(meterRegistry.counter("stockselect.vendor.calls", "vendor", "MarketData.app", "outcome", "failure").count())
                .isEqualTo(1.0);
```

Add these 2 new tests at the end of the class, before the closing brace:

```java
    @Test
    void logsStructuredFieldsForEachVendorCall() {
        wireMock.stubFor(get(urlPathEqualTo("/v1/options/chain/AAPL/"))
                .willReturn(okJson("""
                        { "s": "no_data", "errmsg": "Symbol not found." }
                        """)));

        Logger logbackLogger = (Logger) LoggerFactory.getLogger(MarketDataClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
        try {
            client().getOptionsChain("AAPL").collectList().block();

            ILoggingEvent event = appender.list.get(appender.list.size() - 1);
            Map<String, String> fields = event.getKeyValuePairs().stream()
                    .collect(Collectors.toMap(kv -> kv.key, kv -> String.valueOf(kv.value)));
            assertThat(fields).containsEntry("vendor", "MarketData.app");
            assertThat(fields).containsEntry("status", "success");
            assertThat(fields).containsKey("latencyMs");
        } finally {
            logbackLogger.detachAppender(appender);
        }
    }

    @Test
    void ratelimitGaugeReflectsHealthTrackerValue() {
        client();

        assertThat(meterRegistry.get("stockselect.vendor.ratelimit.remaining")
                .tag("vendor", "MarketData.app").gauge().value()).isNaN();

        healthTracker.recordRateLimit("MarketData.app", 42);

        assertThat(meterRegistry.get("stockselect.vendor.ratelimit.remaining")
                .tag("vendor", "MarketData.app").gauge().value()).isEqualTo(42.0);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -Dtest=MarketDataClientTest`
Expected: FAIL to compile — `MarketDataClient` has no 3-arg constructor yet.

- [ ] **Step 3: Implement**

Replace the full contents of `src/main/java/com/stockselect/marketdata/MarketDataClient.java` with:

```java
package com.stockselect.marketdata;

import com.stockselect.UpstreamApiException;
import com.stockselect.health.VendorHealthTracker;
import com.stockselect.marketdata.dto.OptionsChainResponse;
import com.stockselect.strategy.OptionContract;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Component
public class MarketDataClient {

    private static final Logger log = LoggerFactory.getLogger(MarketDataClient.class);

    public static final String VENDOR = "MarketData.app";
    private static final ZoneId OPTIONS_EXPIRATION_ZONE = ZoneId.of("America/New_York");
    // Matches the 30-60 DTE window shared by every current strategy, plus a small buffer — MarketData.app
    // meters this endpoint per contract returned (not per HTTP call), so fetching a wider range than any
    // strategy actually uses wastes quota on contracts that get filtered out immediately anyway.
    private static final int CHAIN_WINDOW_START_DAYS = 25;
    private static final int CHAIN_WINDOW_END_DAYS = 65;

    private final WebClient webClient;
    private final VendorHealthTracker healthTracker;
    private final MeterRegistry meterRegistry;

    public MarketDataClient(WebClient marketDataWebClient, VendorHealthTracker healthTracker, MeterRegistry meterRegistry) {
        this.webClient = marketDataWebClient.mutate()
                .filter((request, next) -> next.exchange(request)
                        .doOnNext(response -> {
                            String rateLimitHeader = response.headers().asHttpHeaders().getFirst("x-api-ratelimit-remaining");
                            if (rateLimitHeader != null) {
                                try {
                                    healthTracker.recordRateLimit(VENDOR, Integer.parseInt(rateLimitHeader));
                                } catch (NumberFormatException e) {
                                    log.warn("Failed to parse {} rate limit header: {}", VENDOR, rateLimitHeader);
                                }
                            }
                        }))
                .build();
        this.healthTracker = healthTracker;
        this.meterRegistry = meterRegistry;
        // Function-backed: reflects whatever VendorHealthTracker currently knows on every scrape,
        // no push-based bookkeeping needed. NaN (Prometheus/Micrometer's "no value yet" convention)
        // until the first real rate-limit header arrives.
        Gauge.builder("stockselect.vendor.ratelimit.remaining", healthTracker,
                        tracker -> {
                            Integer remaining = tracker.rateLimitRemaining(VENDOR);
                            return remaining == null ? Double.NaN : remaining;
                        })
                .tag("vendor", VENDOR)
                .register(meterRegistry);
    }

    /** Fetches every expiration within a ~25-65 DTE window, matching the DTE band every current strategy uses. */
    public Flux<OptionContract> getOptionsChain(String symbol) {
        LocalDate from = LocalDate.now().plusDays(CHAIN_WINDOW_START_DAYS);
        LocalDate to = LocalDate.now().plusDays(CHAIN_WINDOW_END_DAYS);
        long startNanos = System.nanoTime();

        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/v1/options/chain/{symbol}/")
                        .queryParam("from", from)
                        .queryParam("to", to)
                        .build(symbol))
                .retrieve()
                .bodyToMono(OptionsChainResponse.class)
                .doOnSuccess(response -> {
                    healthTracker.recordSuccess(VENDOR);
                    recordVendorCall("success", startNanos);
                })
                .onErrorMap(WebClientResponseException.class,
                        ex -> new UpstreamApiException(VENDOR, ex.getStatusCode(), ex))
                .onErrorMap(WebClientRequestException.class,
                        ex -> new UpstreamApiException(VENDOR, HttpStatus.GATEWAY_TIMEOUT, ex))
                .doOnError(UpstreamApiException.class, ex -> {
                    healthTracker.recordFailure(VENDOR, ex.getMessage());
                    recordVendorCall("failure", startNanos);
                })
                .flatMapMany(MarketDataClient::toContracts);
    }

    private void recordVendorCall(String outcome, long startNanos) {
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
        meterRegistry.counter("stockselect.vendor.calls", "vendor", VENDOR, "outcome", outcome).increment();
        log.atInfo()
                .addKeyValue("vendor", VENDOR)
                .addKeyValue("status", outcome)
                .addKeyValue("latencyMs", elapsed.toMillis())
                .log("vendor call completed");
    }

    private static Flux<OptionContract> toContracts(OptionsChainResponse response) {
        if (!response.isOk()) {
            return Flux.empty();
        }

        List<OptionContract> contracts = new ArrayList<>(response.size());
        for (int i = 0; i < response.size(); i++) {
            contracts.add(new OptionContract(
                    response.optionSymbol().get(i),
                    response.underlying().get(i),
                    toLocalDate(response.expiration().get(i)),
                    response.side().get(i),
                    response.strike().get(i),
                    response.underlyingPrice().get(i),
                    response.bid().get(i),
                    response.ask().get(i),
                    response.volume().get(i),
                    response.openInterest().get(i),
                    response.delta().get(i),
                    response.gamma().get(i),
                    response.theta().get(i),
                    response.vega().get(i),
                    response.iv().get(i),
                    response.dte().get(i),
                    response.mid().get(i)
            ));
        }
        return Flux.fromIterable(contracts);
    }

    private static LocalDate toLocalDate(long epochSeconds) {
        return Instant.ofEpochSecond(epochSeconds).atZone(OPTIONS_EXPIRATION_ZONE).toLocalDate();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -Dtest=MarketDataClientTest`
Expected: PASS — all 8 tests (6 existing + 2 new).

- [ ] **Step 5: Run the full suite**

Run: `./mvnw test`
Expected: PASS, coverage gate holds.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/stockselect/marketdata/MarketDataClient.java src/test/java/com/stockselect/marketdata/MarketDataClientTest.java
git commit -m "$(cat <<'EOF'
feat(observability): add vendor-call metrics, rate-limit gauge, and
structured logging to MarketDataClient

Records stockselect.vendor.calls the same way EodhdClient now does,
plus a function-backed stockselect.vendor.ratelimit.remaining gauge
registered once in the constructor that reads whatever
VendorHealthTracker.rateLimitRemaining currently knows on every
Prometheus scrape — no push-based bookkeeping, ties directly into the
rate-limit tracking added earlier.
EOF
)"
```

---

## Task 5: End-to-end manual verification

**Files:** none (verification only).

**Interfaces:** none — this task only exercises what Tasks 1-4 built.

- [ ] **Step 1: Run the full test suite one more time**

Run: `./mvnw test`
Expected: PASS, coverage gate holds. This is the same gate every prior task already ran; re-running here confirms nothing regressed across the whole set of changes together.

- [ ] **Step 2: Rebuild the container image**

```bash
podman stop stock-select 2>/dev/null; podman rm stock-select 2>/dev/null
podman build -t stock-select -t localhost/stock-select:latest .
```

Expected: `Successfully tagged localhost/stock-select:latest`.

- [ ] **Step 3: Start the container and wait for it to come up**

```bash
podman run -d -p 8080:8080 --env-file .env --name stock-select stock-select
sleep 8
curl -s http://localhost:8080/health
```

Expected: `200` with the existing `/health` JSON shape (`components`, `groups`, `status`) — unchanged from before this plan.

- [ ] **Step 4: Verify the new endpoints**

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/actuator/prometheus
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/actuator/metrics
curl -s http://localhost:8080/actuator/prometheus | grep stockselect_vendor_calls_total
```

Expected: both status codes `200`. The `stockselect_vendor_calls_total` grep may return nothing yet (no vendor call has happened this run) — that's expected before Step 5.

- [ ] **Step 5: Trigger a real screen request and confirm business metrics + structured logs**

```bash
curl -s http://localhost:8080/api/screen/jade-lizard/AAPL
curl -s http://localhost:8080/actuator/prometheus | grep -E "stockselect_screen_requests_total|stockselect_vendor_calls_total|stockselect_vendor_ratelimit_remaining"
podman logs --tail 20 stock-select | grep -E "screen completed|vendor call completed"
```

Expected: the Prometheus output includes `stockselect_screen_requests_total{...,strategy="jade-lizard",...}`, `stockselect_vendor_calls_total{...,vendor="EODHD",...}` and `vendor="MarketData.app"` series, and `stockselect_vendor_ratelimit_remaining{vendor="MarketData.app",...}`; the container logs show lines ending in `strategy=jade-lizard symbol=AAPL status=... latencyMs=...` and `vendor=... status=... latencyMs=...`.

Note: MarketData.app's free-tier daily quota may already be exhausted from earlier testing sessions (see `marketdata/CLAUDE.md`) — a `429`/failure response here is still a valid verification (it proves the `outcome="failure"` tag and failure log path work), not a blocker.

- [ ] **Step 6: No commit for this task**

This task is verification-only; nothing to commit. If any step's expected result doesn't match, treat it as a regression against the specific Task (1-4) that introduced the behavior being checked, not as new work.
