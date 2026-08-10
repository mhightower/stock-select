package com.stockselect.eodhd;

import com.stockselect.UpstreamApiException;
import com.stockselect.config.EodhdProperties;
import com.stockselect.eodhd.dto.Quote;
import com.stockselect.health.VendorHealthTracker;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

@Component
public class EodhdClient {

    public static final String VENDOR = "EODHD";

    private final WebClient webClient;
    private final EodhdProperties properties;
    private final VendorHealthTracker healthTracker;

    public EodhdClient(WebClient eodhdWebClient, EodhdProperties properties, VendorHealthTracker healthTracker) {
        this.webClient = eodhdWebClient;
        this.properties = properties;
        this.healthTracker = healthTracker;
    }

    public Mono<Quote> getQuote(String symbol) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/real-time/{symbol}")
                        .queryParam("api_token", properties.apiKey())
                        .queryParam("fmt", "json")
                        .build(symbol))
                .retrieve()
                .bodyToMono(Quote.class)
                .doOnSuccess(quote -> healthTracker.recordSuccess(VENDOR))
                .onErrorMap(WebClientResponseException.class,
                        ex -> new UpstreamApiException(VENDOR, ex.getStatusCode(), ex))
                .doOnError(UpstreamApiException.class, ex -> healthTracker.recordFailure(VENDOR, ex.getMessage()));
    }
}
