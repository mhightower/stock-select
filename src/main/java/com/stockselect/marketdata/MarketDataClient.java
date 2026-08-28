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
