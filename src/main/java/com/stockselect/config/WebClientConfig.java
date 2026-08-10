package com.stockselect.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    // MarketData.app option chain responses easily exceed WebClient's 256KB default buffer.
    private static final int OPTIONS_CHAIN_MAX_IN_MEMORY_BYTES = 10 * 1024 * 1024;

    @Bean
    public WebClient eodhdWebClient(EodhdProperties properties) {
        return WebClient.builder()
                .baseUrl(properties.baseUrl())
                .build();
    }

    @Bean
    public WebClient marketDataWebClient(MarketDataProperties properties) {
        ExchangeStrategies exchangeStrategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(OPTIONS_CHAIN_MAX_IN_MEMORY_BYTES))
                .build();
        return WebClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .exchangeStrategies(exchangeStrategies)
                .build();
    }
}
