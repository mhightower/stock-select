package com.stockselect.eodhd;

import com.stockselect.config.EodhdProperties;
import com.stockselect.eodhd.dto.OptionContract;
import com.stockselect.eodhd.dto.OptionsResponse;
import com.stockselect.eodhd.dto.Quote;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class EodhdClient {

    private static final int PAGE_LIMIT = 1000;

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
                .bodyToMono(Quote.class);
    }

    /** Fetches the full options chain for a symbol across all listed expirations. */
    public Flux<OptionContract> getOptionsChain(String symbol) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/mp/unicornbay/options/eod")
                        .queryParam("filter[underlying_symbol]", symbol)
                        .queryParam("page[limit]", PAGE_LIMIT)
                        .queryParam("api_token", properties.apiKey())
                        .build())
                .retrieve()
                .bodyToMono(OptionsResponse.class)
                .flatMapMany(response -> Flux.fromIterable(response.data()))
                .map(OptionsResponse.OptionData::attributes);
    }
}
