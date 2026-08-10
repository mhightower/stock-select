package com.stockselect.eodhd;

import com.stockselect.UpstreamApiException;
import com.stockselect.config.EodhdProperties;
import com.stockselect.eodhd.dto.Quote;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

@Component
public class EodhdClient {

    private static final String VENDOR = "EODHD";

    private final WebClient webClient;
    private final EodhdProperties properties;

    public EodhdClient(WebClient eodhdWebClient, EodhdProperties properties) {
        this.webClient = eodhdWebClient;
        this.properties = properties;
    }

    public Mono<Quote> getQuote(String symbol) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/real-time/{symbol}")
                        .queryParam("api_token", properties.apiKey())
                        .queryParam("fmt", "json")
                        .build(symbol))
                .retrieve()
                .bodyToMono(Quote.class)
                .onErrorMap(WebClientResponseException.class,
                        ex -> new UpstreamApiException(VENDOR, ex.getStatusCode(), ex));
    }
}
