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
