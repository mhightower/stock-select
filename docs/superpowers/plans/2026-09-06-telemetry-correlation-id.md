# Request Correlation IDs & Latency Percentiles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every screen request a correlation ID that ties its log lines together across `ScreeningController` → `ScreeningService` → `EodhdClient`/`MarketDataClient`, and add percentile/SLO visibility into both screen and vendor-call latency.

**Architecture:** A new `CorrelationIdFilter` (a `OncePerRequestFilter`) assigns an `X-Request-Id` on every request (honoring one the caller supplies, generating a UUID otherwise), echoes it as a response header, and exposes it as a request attribute. `ScreeningController` reads that attribute and threads it as a plain `String requestId` parameter through `ScreeningService.screen(...)` into `EodhdClient.getQuote(...)` and `MarketDataClient.getOptionsChain(...)`, where it's attached to each vendor-call log line. Separately, `Timer.builder(...).publishPercentileHistogram()` + explicit SLO buckets are added to the existing `stockselect.screen.latency` timer and a new `stockselect.vendor.latency` timer in both vendor clients.

**Tech Stack:** Spring Boot 4.1.1 (Jakarta Servlet namespace), Micrometer (via `spring-boot-starter-actuator`), SLF4J 2.x fluent logging, JUnit 5 + AssertJ + Mockito, WireMock (test scope).

**Spec:** `docs/superpowers/specs/2026-09-06-telemetry-correlation-id-design.md`

## Global Constraints

- No new dependencies — everything is available today via `spring-boot-starter-actuator` (Micrometer) and `spring-boot-starter-web` (Jakarta Servlet API, `OncePerRequestFilter`).
- Correlation ID propagation is explicit-parameter only, never MDC — MDC does not reliably cross this app's virtual-thread executor or the vendor clients' Reactor/Netty callback threads.
- Response header name: `X-Request-Id`. Request attribute name: `requestId`. Log field name: `requestId` (matches the attribute name, camelCase like the existing `strategy`/`symbol`/`status`/`latencyMs` fields).
- Honor an inbound `X-Request-Id` request header if present and non-blank; otherwise generate `UUID.randomUUID().toString()`. Always echo the resulting value as the `X-Request-Id` response header on every request, including error responses.
- Percentile/SLO buckets, identical on both timers: `Duration.ofMillis(250)`, `Duration.ofMillis(500)`, `Duration.ofSeconds(1)`, `Duration.ofSeconds(2)`, `Duration.ofSeconds(5)`, via `.publishPercentileHistogram()` + `.serviceLevelObjectives(...)` (not `.publishPercentiles(...)`, which can't be validly aggregated across instances in Prometheus).
- Toolchain: use `./mvnw`, not a system `mvn`. This shell has no JDK on `PATH` by default —
  ```bash
  export JAVA_HOME=~/tools/jdk-26.0.2+10
  export PATH="$JAVA_HOME/bin:$PATH"
  ```
  Run this once per shell session before any `./mvnw` command below.
- Run `./mvnw test` and confirm it passes before every commit in this plan — no task's commit should leave the build red.

---

### Task 1: `CorrelationIdFilter`

**Files:**
- Create: `src/main/java/com/stockselect/web/CorrelationIdFilter.java`
- Test: `src/test/java/com/stockselect/web/CorrelationIdFilterTest.java`
- Modify: `src/test/java/com/stockselect/StockSelectApplicationTests.java`

**Interfaces:**
- Produces: `CorrelationIdFilter.REQUEST_ID_HEADER` (`"X-Request-Id"`, `public static final String`), `CorrelationIdFilter.REQUEST_ID_ATTRIBUTE` (`"requestId"`, `public static final String`) — Task 2's `ScreeningController` reads `request.getAttribute(CorrelationIdFilter.REQUEST_ID_ATTRIBUTE)`.

This task is fully additive — no existing file's behavior changes, so it can be built, tested, and committed on its own.

- [ ] **Step 1: Write the failing filter test**

```java
package com.stockselect.web;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void generatesARequestIdWhenNoneIsSupplied() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        String generated = response.getHeader(CorrelationIdFilter.REQUEST_ID_HEADER);
        assertThat(generated).isNotBlank();
        assertThat(request.getAttribute(CorrelationIdFilter.REQUEST_ID_ATTRIBUTE)).isEqualTo(generated);
    }

    @Test
    void echoesAnInboundRequestIdUnchanged() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.REQUEST_ID_HEADER, "caller-supplied-id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(CorrelationIdFilter.REQUEST_ID_HEADER)).isEqualTo("caller-supplied-id");
        assertThat(request.getAttribute(CorrelationIdFilter.REQUEST_ID_ATTRIBUTE)).isEqualTo("caller-supplied-id");
    }

    @Test
    void treatsABlankInboundHeaderAsAbsentAndGeneratesAnId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.REQUEST_ID_HEADER, "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(CorrelationIdFilter.REQUEST_ID_HEADER)).isNotBlank();
        assertThat(response.getHeader(CorrelationIdFilter.REQUEST_ID_HEADER)).isNotEqualTo("   ");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME=~/tools/jdk-26.0.2+10 && export PATH="$JAVA_HOME/bin:$PATH" && ./mvnw test -Dtest=CorrelationIdFilterTest`
Expected: FAIL to compile — `CorrelationIdFilter` does not exist.

- [ ] **Step 3: Create `CorrelationIdFilter`**

```java
package com.stockselect.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Assigns every request an {@code X-Request-Id} — honoring one the caller supplies, generating a
 * UUID otherwise — echoes it as a response header, and exposes it as a request attribute so
 * {@code ScreeningController} can thread it through to {@code ScreeningService} and the vendor
 * clients. Runs as a servlet {@code Filter} rather than a {@code HandlerInterceptor} so every
 * response gets an ID, including paths with no matching handler (the existing 404 case).
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String REQUEST_ID_ATTRIBUTE = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        response.setHeader(REQUEST_ID_HEADER, requestId);
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        filterChain.doFilter(request, response);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw test -Dtest=CorrelationIdFilterTest`
Expected: PASS (3 tests)

- [ ] **Step 5: Add a whole-context assertion that the filter is wired globally**

In `src/test/java/com/stockselect/StockSelectApplicationTests.java`, add:

```java
    @Test
    void includesACorrelationIdHeaderOnEveryResponse() {
        ResponseEntity<String> response = restTemplate.getForEntity("/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotBlank();
    }
```

Add this as a new method inside the existing class, after `servesMetricsListUnderActuator()`. No new imports needed — `ResponseEntity`, `HttpStatus`, and `assertThat` are already imported in this file.

- [ ] **Step 6: Run the full suite to verify everything passes**

Run: `./mvnw test`
Expected: PASS, all tests green (this confirms `CorrelationIdFilter` is auto-detected as a `@Component` and applied to every path, including `/health`, without any explicit registration).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/stockselect/web/CorrelationIdFilter.java \
        src/test/java/com/stockselect/web/CorrelationIdFilterTest.java \
        src/test/java/com/stockselect/StockSelectApplicationTests.java
git commit -m "$(cat <<'EOF'
feat(web): add CorrelationIdFilter assigning an X-Request-Id per request

Lays the groundwork for correlating a request's log lines across
ScreeningService and the vendor clients. Runs as a servlet Filter (not
a HandlerInterceptor) so every response gets an ID, including paths
with no matching handler.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Thread the correlation ID through the screening call chain

**Files:**
- Modify: `src/main/java/com/stockselect/web/ScreeningController.java`
- Modify: `src/main/java/com/stockselect/screening/ScreeningService.java`
- Modify: `src/main/java/com/stockselect/eodhd/EodhdClient.java`
- Modify: `src/main/java/com/stockselect/marketdata/MarketDataClient.java`
- Modify: `src/test/java/com/stockselect/web/ScreeningControllerTest.java`
- Modify: `src/test/java/com/stockselect/screening/ScreeningServiceTest.java`
- Modify: `src/test/java/com/stockselect/eodhd/EodhdClientTest.java`
- Modify: `src/test/java/com/stockselect/marketdata/MarketDataClientTest.java`

**Interfaces:**
- Consumes: `CorrelationIdFilter.REQUEST_ID_ATTRIBUTE` from Task 1.
- Produces: `ScreeningService.screen(String symbol, String strategyName, String requestId)`, `EodhdClient.getQuote(String symbol, String requestId)`, `MarketDataClient.getOptionsChain(String symbol, String requestId)` — the new signatures Task 3 builds on.

This is one task, not several, because the signature change ripples through all four production files and their four test files simultaneously — the build only compiles once every call site is updated together, so there's no smaller unit a reviewer could approve independently.

- [ ] **Step 1: Update `EodhdClientTest` for the new `getQuote` signature and write the new `requestId` log assertion**

Replace every `client().getQuote("AAPL")` call in `EodhdClientTest.java` with `client().getQuote("AAPL", "test-request-id")` (4 call sites: `parsesQuoteResponseAndRecordsSuccess`, `wrapsAVendorErrorResponseInAnUpstreamApiExceptionAndRecordsFailure`, `mapsASlowVendorResponseToAGatewayTimeoutUpstreamExceptionAndRecordsFailure` — note this one calls `client.getQuote("AAPL")` on a locally-built `client`, not `client()` — and `logsStructuredFieldsForEachVendorCall`).

In `logsStructuredFieldsForEachVendorCall`, add after the existing `containsKey("latencyMs")` assertion:

```java
            assertThat(fields).containsEntry("requestId", "test-request-id");
```

- [ ] **Step 2: Run the EODHD test to verify it fails to compile**

Run: `./mvnw test -Dtest=EodhdClientTest`
Expected: FAIL to compile — `getQuote(String, String)` does not exist yet.

- [ ] **Step 3: Update `EodhdClient.getQuote` and `recordVendorCall`**

```java
    public Mono<Quote> getQuote(String symbol, String requestId) {
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
                    recordVendorCall("success", startNanos, requestId);
                })
                .onErrorMap(WebClientResponseException.class,
                        ex -> new UpstreamApiException(VENDOR, ex.getStatusCode(), ex))
                .onErrorMap(WebClientRequestException.class,
                        ex -> new UpstreamApiException(VENDOR, HttpStatus.GATEWAY_TIMEOUT, ex))
                .doOnError(UpstreamApiException.class, ex -> {
                    healthTracker.recordFailure(VENDOR, ex.getMessage());
                    recordVendorCall("failure", startNanos, requestId);
                });
    }

    private void recordVendorCall(String outcome, long startNanos, String requestId) {
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
        meterRegistry.counter("stockselect.vendor.calls", "vendor", VENDOR, "outcome", outcome).increment();
        log.atInfo()
                .addKeyValue("vendor", VENDOR)
                .addKeyValue("status", outcome)
                .addKeyValue("latencyMs", elapsed.toMillis())
                .addKeyValue("requestId", requestId)
                .log("vendor call completed");
    }
```

This replaces the existing `getQuote` and `recordVendorCall` methods in `EodhdClient.java` in place — same file, same method names, two new `requestId` parameters threaded through.

- [ ] **Step 4: Run the EODHD test to verify it passes**

Run: `./mvnw test -Dtest=EodhdClientTest`
Expected: PASS (4 tests)

- [ ] **Step 5: Update `MarketDataClientTest` for the new `getOptionsChain` signature and write the new `requestId` log assertion**

Replace every `client().getOptionsChain("AAPL")` / `client().getOptionsChain("ZZZZ")` call in `MarketDataClientTest.java` with a second `"test-request-id"` argument (6 call sites: `parsesTheParallelArrayChainResponse`, `requestsOnlyThe25To65DteWindowSharedByAllStrategies`, `returnsNoContractsWhenTheResponseStatusIsNotOkButStillRecordsVendorSuccess`, `wrapsAVendorErrorResponseInAnUpstreamApiExceptionAndRecordsFailure`, `extractsAndRecordsRateLimitRemainingFromResponseHeader`, `logsStructuredFieldsForEachVendorCall` — plus `mapsASlowVendorResponseToAGatewayTimeoutUpstreamExceptionAndRecordsFailure`, which calls `client.getOptionsChain("AAPL")` on a locally-built `client`).

In `logsStructuredFieldsForEachVendorCall`, add after the existing `containsKey("latencyMs")` assertion:

```java
            assertThat(fields).containsEntry("requestId", "test-request-id");
```

- [ ] **Step 6: Run the MarketData test to verify it fails to compile**

Run: `./mvnw test -Dtest=MarketDataClientTest`
Expected: FAIL to compile — `getOptionsChain(String, String)` does not exist yet.

- [ ] **Step 7: Update `MarketDataClient.getOptionsChain` and `recordVendorCall`**

```java
    public Flux<OptionContract> getOptionsChain(String symbol, String requestId) {
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
                    recordVendorCall("success", startNanos, requestId);
                })
                .onErrorMap(WebClientResponseException.class,
                        ex -> new UpstreamApiException(VENDOR, ex.getStatusCode(), ex))
                .onErrorMap(WebClientRequestException.class,
                        ex -> new UpstreamApiException(VENDOR, HttpStatus.GATEWAY_TIMEOUT, ex))
                .doOnError(UpstreamApiException.class, ex -> {
                    healthTracker.recordFailure(VENDOR, ex.getMessage());
                    recordVendorCall("failure", startNanos, requestId);
                })
                .flatMapMany(MarketDataClient::toContracts);
    }

    private void recordVendorCall(String outcome, long startNanos, String requestId) {
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
        meterRegistry.counter("stockselect.vendor.calls", "vendor", VENDOR, "outcome", outcome).increment();
        log.atInfo()
                .addKeyValue("vendor", VENDOR)
                .addKeyValue("status", outcome)
                .addKeyValue("latencyMs", elapsed.toMillis())
                .addKeyValue("requestId", requestId)
                .log("vendor call completed");
    }
```

This replaces the existing `getOptionsChain` and `recordVendorCall` methods in `MarketDataClient.java` in place. `toContracts` and `toLocalDate` are unchanged.

- [ ] **Step 8: Run the MarketData test to verify it passes**

Run: `./mvnw test -Dtest=MarketDataClientTest`
Expected: PASS (8 tests)

- [ ] **Step 9: Update `ScreeningServiceTest` for the new `screen` signature and write the new `requestId` log assertion**

Replace every `service.screen("...", "...")` call with a third `"test-request-id"` argument, and every `eodhdClient.getQuote("AAPL")` / `marketDataClient.getOptionsChain("AAPL")` stub with a second `"test-request-id"` argument, across all 6 test methods (`dispatchesToTheMatchingStrategyWithABareUppercasedSymbol`, `throwsForAnUnknownStrategyName`, `fallsBackToMarketDataPriceAndWarnsWhenEodhdIsUnavailable`, `recordsScreenRequestMetricsOnSuccess`, `recordsAFailureMetricWithAnUnknownStrategySentinelForAnUnknownStrategyName`, `recordsAFailureMetricWhenMarketDataFails`, `logsStructuredFieldsOnScreenCompletion`).

For example, `dispatchesToTheMatchingStrategyWithABareUppercasedSymbol` becomes:

```java
    @Test
    void dispatchesToTheMatchingStrategyWithABareUppercasedSymbol() {
        Quote quote = new Quote("AAPL.US", 0L, 190, 195, 189, 193.5, 1_000_000, 191, 2.5, 1.31);
        when(eodhdClient.getQuote("AAPL", "test-request-id")).thenReturn(Mono.just(quote));
        when(marketDataClient.getOptionsChain("AAPL", "test-request-id")).thenReturn(Flux.empty());

        ScreeningService service = new ScreeningService(eodhdClient, marketDataClient, List.of(new StubStrategy("jade-lizard")), meterRegistry);

        ScreeningResult result = service.screen("aapl.us", "jade-lizard", "test-request-id");

        assertThat(result.warnings()).isEmpty();
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).symbol()).isEqualTo("AAPL");
        assertThat(result.candidates().get(0).underlyingPrice()).isEqualTo(193.5);
    }
```

Apply the same pattern (stubs gain `"test-request-id"` as the last argument, `service.screen(...)` gains `"test-request-id"` as the third argument) to the other 5 methods — `throwsForAnUnknownStrategyName`'s `service.screen("AAPL", "iron-condor")` becomes `service.screen("AAPL", "iron-condor", "test-request-id")` (no stub changes needed there, since it fails before either client is called).

In `logsStructuredFieldsOnScreenCompletion`, add after the existing `containsKey("latencyMs")` assertion:

```java
            assertThat(fields).containsEntry("requestId", "test-request-id");
```

- [ ] **Step 10: Run the ScreeningService test to verify it fails to compile**

Run: `./mvnw test -Dtest=ScreeningServiceTest`
Expected: FAIL to compile — `screen(String, String, String)` does not exist yet.

- [ ] **Step 11: Update `ScreeningService.screen`**

```java
    public ScreeningResult screen(String symbol, String strategyName, String requestId) {
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
        String normalizedSymbol = symbol;
        try {
            // MarketData.app rejects exchange-suffixed symbols (e.g. "AAPL.US") outright; EODHD
            // accepts the bare ticker fine, so normalize to bare for both clients.
            normalizedSymbol = symbol.toUpperCase().replaceFirst("\\.US$", "");

            List<OptionContract> optionsChain;
            List<String> warnings = new ArrayList<>();
            double underlyingPrice;
            // The chain and quote calls are independent, so fetch them concurrently on their own
            // virtual threads instead of serially — halves the vendor latency on the happy path.
            // normalizedSymbol is reassigned above (not effectively final), so the lambdas below
            // need their own final copy to capture.
            final String vendorSymbol = normalizedSymbol;
            try (var vthreads = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<List<OptionContract>> chainFuture =
                        vthreads.submit(() -> marketDataClient.getOptionsChain(vendorSymbol, requestId).collectList().block());
                Future<Quote> quoteFuture = vthreads.submit(() -> eodhdClient.getQuote(vendorSymbol, requestId).block());

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
                    .addKeyValue("symbol", normalizedSymbol)
                    .addKeyValue("status", outcome)
                    .addKeyValue("latencyMs", elapsed.toMillis())
                    .addKeyValue("requestId", requestId)
                    .log("screen completed");
        }
    }
```

This replaces the existing `screen` method in `ScreeningService.java` in place — one new parameter, two new arguments at the vendor-client call sites, one new `addKeyValue` on the log line. The `Timer.builder(...)` percentile/SLO config is added in Task 3, not here — keep this task scoped to the `requestId` threading only.

- [ ] **Step 12: Run the ScreeningService test to verify it passes**

Run: `./mvnw test -Dtest=ScreeningServiceTest`
Expected: PASS (7 tests)

- [ ] **Step 13: Update `ScreeningControllerTest` for the new `screen` signature and add correlation-id assertions**

Add these two imports:

```java
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
```

Replace every `when(screeningService.screen("AAPL", "jade-lizard"))` / `when(screeningService.screen("AAPL", "iron-condor"))` with a 3-argument stub using `eq(...)` for the first two arguments and `any(String.class)` for the third, across all 6 existing test methods. For example:

```java
        when(screeningService.screen(eq("AAPL"), eq("jade-lizard"), any(String.class)))
                .thenReturn(new ScreeningResult(List.of(candidate), List.of()));
```

Apply the same `eq(...)`/`eq(...)`/`any(String.class)` pattern to the other 5 stubs (`includesWarningsWhenEodhdDegradedGracefully`, `returnsBadRequestForAnUnknownStrategy`, `returnsTooManyRequestsWhenAVendorRateLimitsUs`, `returnsBadGatewayWhenAVendorRejectsTheApiKey`, `returnsBadGatewayWithAGenericMessageForOtherVendorFailures`).

Add two new test methods:

```java
    @Test
    void includesAGeneratedCorrelationIdHeaderOnASuccessfulResponse() throws Exception {
        TradeCandidate candidate = new TradeCandidate(
                "jade-lizard", "AAPL.US", "USD", 193.5, null,
                200.0, null, 180.0, 175.0, 0.16, -0.15,
                1.5, 5.0, 3.5, 201.5, 178.5, "CUSTOM AAPL.US 100 ...");
        when(screeningService.screen(eq("AAPL"), eq("jade-lizard"), any(String.class)))
                .thenReturn(new ScreeningResult(List.of(candidate), List.of()));

        mockMvc.perform(get("/api/screen/jade-lizard/AAPL"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void honorsAndPassesThroughAnInboundCorrelationId() throws Exception {
        TradeCandidate candidate = new TradeCandidate(
                "jade-lizard", "AAPL.US", "USD", 193.5, null,
                200.0, null, 180.0, 175.0, 0.16, -0.15,
                1.5, 5.0, 3.5, 201.5, 178.5, "CUSTOM AAPL.US 100 ...");
        when(screeningService.screen(eq("AAPL"), eq("jade-lizard"), eq("caller-supplied-id")))
                .thenReturn(new ScreeningResult(List.of(candidate), List.of()));

        mockMvc.perform(get("/api/screen/jade-lizard/AAPL").header("X-Request-Id", "caller-supplied-id"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "caller-supplied-id"));
    }
```

Add the import these two new methods need:

```java
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
```

- [ ] **Step 14: Run the ScreeningController test to verify it fails to compile**

Run: `./mvnw test -Dtest=ScreeningControllerTest`
Expected: FAIL to compile — `screen(String, String, String)` does not exist yet.

- [ ] **Step 15: Update `ScreeningController`**

```java
package com.stockselect.web;

import com.stockselect.screening.ScreeningResult;
import com.stockselect.screening.ScreeningService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/screen")
public class ScreeningController {

    private final ScreeningService screeningService;

    public ScreeningController(ScreeningService screeningService) {
        this.screeningService = screeningService;
    }

    @GetMapping("/{strategy}/{symbol}")
    public ScreeningResult screen(@PathVariable String strategy, @PathVariable String symbol, HttpServletRequest request) {
        String requestId = (String) request.getAttribute(CorrelationIdFilter.REQUEST_ID_ATTRIBUTE);
        return screeningService.screen(symbol, strategy, requestId);
    }
}
```

This replaces the whole file — same package (`com.stockselect.web`), so `CorrelationIdFilter` needs no import.

- [ ] **Step 16: Run the ScreeningController test to verify it passes**

Run: `./mvnw test -Dtest=ScreeningControllerTest`
Expected: PASS (8 tests)

- [ ] **Step 17: Run the full suite**

Run: `./mvnw test`
Expected: PASS, all tests green.

- [ ] **Step 18: Commit**

```bash
git add src/main/java/com/stockselect/web/ScreeningController.java \
        src/main/java/com/stockselect/screening/ScreeningService.java \
        src/main/java/com/stockselect/eodhd/EodhdClient.java \
        src/main/java/com/stockselect/marketdata/MarketDataClient.java \
        src/test/java/com/stockselect/web/ScreeningControllerTest.java \
        src/test/java/com/stockselect/screening/ScreeningServiceTest.java \
        src/test/java/com/stockselect/eodhd/EodhdClientTest.java \
        src/test/java/com/stockselect/marketdata/MarketDataClientTest.java
git commit -m "$(cat <<'EOF'
feat(observability): thread a correlation ID through the screening call chain

ScreeningController now reads the X-Request-Id CorrelationIdFilter
attached to the request and passes it through ScreeningService into
EodhdClient/MarketDataClient, attached as a requestId field on every
log line each of them already emits. Closes the gap where concurrent
requests' log lines were impossible to tell apart.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Percentile/SLO config on screen and vendor-call latency

**Files:**
- Modify: `src/main/java/com/stockselect/screening/ScreeningService.java`
- Modify: `src/main/java/com/stockselect/eodhd/EodhdClient.java`
- Modify: `src/main/java/com/stockselect/marketdata/MarketDataClient.java`
- Modify: `src/test/java/com/stockselect/screening/ScreeningServiceTest.java`
- Modify: `src/test/java/com/stockselect/eodhd/EodhdClientTest.java`
- Modify: `src/test/java/com/stockselect/marketdata/MarketDataClientTest.java`

**Interfaces:**
- Consumes: `ScreeningService.screen(String, String, String)`, `EodhdClient.recordVendorCall(String, long, String)`, `MarketDataClient.recordVendorCall(String, long, String)` from Task 2.
- Produces: a new `stockselect.vendor.latency` `Timer` (tags: `vendor`, `outcome`) registered in both vendor clients, alongside the existing `stockselect.vendor.calls` `Counter`.

- [ ] **Step 1: Write the failing percentile-histogram test for `stockselect.screen.latency`**

Add to `ScreeningServiceTest.java`:

```java
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
```

```java
    @Test
    void publishesLatencyHistogramBucketsForScreenLatency() {
        Quote quote = new Quote("AAPL.US", 0L, 190, 195, 189, 193.5, 1_000_000, 191, 2.5, 1.31);
        when(eodhdClient.getQuote("AAPL", "test-request-id")).thenReturn(Mono.just(quote));
        when(marketDataClient.getOptionsChain("AAPL", "test-request-id")).thenReturn(Flux.empty());
        ScreeningService service = new ScreeningService(eodhdClient, marketDataClient, List.of(new StubStrategy("jade-lizard")), meterRegistry);

        service.screen("AAPL", "jade-lizard", "test-request-id");

        HistogramSnapshot snapshot = meterRegistry
                .timer("stockselect.screen.latency", "strategy", "jade-lizard", "outcome", "success")
                .takeSnapshot();
        assertThat(snapshot.histogramCounts()).isNotEmpty();
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=ScreeningServiceTest#publishesLatencyHistogramBucketsForScreenLatency`
Expected: FAIL — `histogramCounts()` is empty (no percentile histogram configured yet).

- [ ] **Step 3: Add percentile/SLO config to `stockselect.screen.latency`**

In `ScreeningService.screen`'s `finally` block, replace:

```java
            Timer.builder("stockselect.screen.latency")
                    .tag("strategy", strategyName)
                    .tag("outcome", outcome)
                    .register(meterRegistry)
                    .record(elapsed);
```

with:

```java
            Timer.builder("stockselect.screen.latency")
                    .tag("strategy", strategyName)
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .serviceLevelObjectives(
                            Duration.ofMillis(250), Duration.ofMillis(500),
                            Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(5))
                    .register(meterRegistry)
                    .record(elapsed);
```

`Duration` is already imported in `ScreeningService.java`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw test -Dtest=ScreeningServiceTest`
Expected: PASS (8 tests)

- [ ] **Step 5: Write the failing `stockselect.vendor.latency` tests for `EodhdClient`**

Add to `EodhdClientTest.java`, at the end of `parsesQuoteResponseAndRecordsSuccess`:

```java
        assertThat(meterRegistry.timer("stockselect.vendor.latency", "vendor", "EODHD", "outcome", "success").count())
                .isEqualTo(1L);
```

Add to `wrapsAVendorErrorResponseInAnUpstreamApiExceptionAndRecordsFailure`, at the end:

```java
        assertThat(meterRegistry.timer("stockselect.vendor.latency", "vendor", "EODHD", "outcome", "failure").count())
                .isEqualTo(1L);
```

- [ ] **Step 6: Run the test to verify it fails**

Run: `./mvnw test -Dtest=EodhdClientTest`
Expected: FAIL — `meterRegistry.timer("stockselect.vendor.latency", ...)` throws `MeterNotFoundException` (no such timer registered yet).

- [ ] **Step 7: Register `stockselect.vendor.latency` in `EodhdClient`**

Add the import:

```java
import io.micrometer.core.instrument.Timer;
```

Replace `recordVendorCall` (from Task 2's version) with:

```java
    private void recordVendorCall(String outcome, long startNanos, String requestId) {
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
        meterRegistry.counter("stockselect.vendor.calls", "vendor", VENDOR, "outcome", outcome).increment();
        Timer.builder("stockselect.vendor.latency")
                .tag("vendor", VENDOR)
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .serviceLevelObjectives(
                        Duration.ofMillis(250), Duration.ofMillis(500),
                        Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(5))
                .register(meterRegistry)
                .record(elapsed);
        log.atInfo()
                .addKeyValue("vendor", VENDOR)
                .addKeyValue("status", outcome)
                .addKeyValue("latencyMs", elapsed.toMillis())
                .addKeyValue("requestId", requestId)
                .log("vendor call completed");
    }
```

- [ ] **Step 8: Run the test to verify it passes**

Run: `./mvnw test -Dtest=EodhdClientTest`
Expected: PASS (4 tests)

- [ ] **Step 9: Write the failing `stockselect.vendor.latency` tests for `MarketDataClient`**

Add to `MarketDataClientTest.java`, at the end of `parsesTheParallelArrayChainResponse`:

```java
        assertThat(meterRegistry.timer("stockselect.vendor.latency", "vendor", "MarketData.app", "outcome", "success").count())
                .isEqualTo(1L);
```

Add to `wrapsAVendorErrorResponseInAnUpstreamApiExceptionAndRecordsFailure`, at the end:

```java
        assertThat(meterRegistry.timer("stockselect.vendor.latency", "vendor", "MarketData.app", "outcome", "failure").count())
                .isEqualTo(1L);
```

- [ ] **Step 10: Run the test to verify it fails**

Run: `./mvnw test -Dtest=MarketDataClientTest`
Expected: FAIL — `meterRegistry.timer("stockselect.vendor.latency", ...)` throws `MeterNotFoundException`.

- [ ] **Step 11: Register `stockselect.vendor.latency` in `MarketDataClient`**

Add the import:

```java
import io.micrometer.core.instrument.Timer;
```

Replace `recordVendorCall` (from Task 2's version) with:

```java
    private void recordVendorCall(String outcome, long startNanos, String requestId) {
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
        meterRegistry.counter("stockselect.vendor.calls", "vendor", VENDOR, "outcome", outcome).increment();
        Timer.builder("stockselect.vendor.latency")
                .tag("vendor", VENDOR)
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .serviceLevelObjectives(
                        Duration.ofMillis(250), Duration.ofMillis(500),
                        Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(5))
                .register(meterRegistry)
                .record(elapsed);
        log.atInfo()
                .addKeyValue("vendor", VENDOR)
                .addKeyValue("status", outcome)
                .addKeyValue("latencyMs", elapsed.toMillis())
                .addKeyValue("requestId", requestId)
                .log("vendor call completed");
    }
```

- [ ] **Step 12: Run the test to verify it passes**

Run: `./mvnw test -Dtest=MarketDataClientTest`
Expected: PASS (8 tests)

- [ ] **Step 13: Run the full suite, including the JaCoCo coverage gate**

Run: `./mvnw test`
Expected: PASS, all tests green, JaCoCo's 80% line-coverage gate satisfied (bound to the `test` phase — a failure here means the build fails, not just a warning).

- [ ] **Step 14: Commit**

```bash
git add src/main/java/com/stockselect/screening/ScreeningService.java \
        src/main/java/com/stockselect/eodhd/EodhdClient.java \
        src/main/java/com/stockselect/marketdata/MarketDataClient.java \
        src/test/java/com/stockselect/screening/ScreeningServiceTest.java \
        src/test/java/com/stockselect/eodhd/EodhdClientTest.java \
        src/test/java/com/stockselect/marketdata/MarketDataClientTest.java
git commit -m "$(cat <<'EOF'
feat(observability): add percentile/SLO buckets to screen and vendor latency

stockselect.screen.latency gains publishPercentileHistogram() plus
explicit SLO buckets (250ms/500ms/1s/2s/5s) instead of only exposing
count/sum. EodhdClient and MarketDataClient each gain a new
stockselect.vendor.latency timer with the same config, closing the gap
where vendor-call latency was logged but not queryable in Prometheus.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review Notes

**Spec coverage:**
- Correlation ID generation/honoring/echoing → Task 1 (`CorrelationIdFilter`).
- Threading through `ScreeningController` → `ScreeningService` → `EodhdClient`/`MarketDataClient`, log field on every line → Task 2.
- `stockselect.screen.latency` percentiles/SLOs → Task 3, Steps 1-4.
- `stockselect.vendor.latency` new timer (both clients) → Task 3, Steps 5-12.
- Response header contract (`X-Request-Id` on every response, including error paths) → Task 1 Step 5 (whole-context `/health` check) + Task 2 Step 13 (`ScreeningControllerTest`'s success-path and inbound-echo assertions covers the `/api/screen/**` case; the unknown-strategy 400 path already gets the header for free since the filter sets it before `filterChain.doFilter` runs, independent of what the controller/service later throw).
- Explicitly-out-of-scope items (tracing, alerting/dashboards, the pre-existing unknown-strategy missing log line) — deliberately have no task, matching the spec.

**Placeholder scan:** no TBD/TODO; every step has literal code, not a description of code.

**Type consistency:** `screen(String, String, String)`, `getQuote(String, String)`, `getOptionsChain(String, String)`, `recordVendorCall(String, long, String)`, `CorrelationIdFilter.REQUEST_ID_HEADER`/`REQUEST_ID_ATTRIBUTE` are used identically across Tasks 1-3 wherever they appear.
