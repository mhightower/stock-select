package com.stockselect.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
public class WebClientConfig {

    // MarketData.app option chain responses easily exceed WebClient's 256KB default buffer.
    private static final int OPTIONS_CHAIN_MAX_IN_MEMORY_BYTES = 10 * 1024 * 1024;

    // Neither vendor call had a timeout before this — a hung connection or a stalled response
    // blocked the calling virtual thread (see ScreeningService's .block() calls) indefinitely,
    // with no way for ApiExceptionHandler or VendorHealthTracker to ever see it.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(15);

    @Bean
    public WebClient eodhdWebClient(EodhdProperties properties) {
        return WebClient.builder()
                .baseUrl(properties.baseUrl())
                .clientConnector(timeoutConnector(CONNECT_TIMEOUT, RESPONSE_TIMEOUT))
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
                .clientConnector(timeoutConnector(CONNECT_TIMEOUT, RESPONSE_TIMEOUT))
                .build();
    }

    /** Exposed so client tests can exercise the same wiring with short durations instead of waiting out the real ones. */
    public static ReactorClientHttpConnector timeoutConnector(Duration connectTimeout, Duration responseTimeout) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeout.toMillis())
                .responseTimeout(responseTimeout);
        return new ReactorClientHttpConnector(httpClient);
    }
}
