package com.stockselect.eodhd;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.stockselect.UpstreamApiException;
import com.stockselect.config.EodhdProperties;
import com.stockselect.config.WebClientConfig;
import com.stockselect.eodhd.dto.Quote;
import com.stockselect.health.VendorHealthTracker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies EodhdClient parses EODHD's actual quote response shape, against a WireMock server
 * standing in for eodhd.com.
 */
class EodhdClientTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private final VendorHealthTracker healthTracker = new VendorHealthTracker();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private EodhdClient client() {
        EodhdProperties properties = new EodhdProperties(wireMock.baseUrl(), "test-token");
        WebClient webClient = WebClient.builder().baseUrl(properties.baseUrl()).build();
        return new EodhdClient(webClient, properties, healthTracker, meterRegistry);
    }

    @Test
    void parsesQuoteResponseAndRecordsSuccess() {
        wireMock.stubFor(get(urlPathEqualTo("/api/real-time/AAPL"))
                .withQueryParam("api_token", equalTo("test-token"))
                .withQueryParam("fmt", equalTo("json"))
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

        Quote quote = client().getQuote("AAPL").block();

        assertThat(quote).isNotNull();
        assertThat(quote.code()).isEqualTo("AAPL.US");
        assertThat(quote.close()).isEqualTo(193.5);
        assertThat(quote.previousClose()).isEqualTo(191.0);
        assertThat(quote.changePercent()).isEqualTo(1.31);
        assertThat(healthTracker.outcome("EODHD")).isEqualTo(VendorHealthTracker.Outcome.UP);
        assertThat(meterRegistry.counter("stockselect.vendor.calls", "vendor", "EODHD", "outcome", "success").count())
                .isEqualTo(1.0);
    }

    @Test
    void wrapsAVendorErrorResponseInAnUpstreamApiExceptionAndRecordsFailure() {
        wireMock.stubFor(get(urlPathEqualTo("/api/real-time/AAPL"))
                .willReturn(aResponse().withStatus(429).withBody("Too Many Requests")));

        Mono<Quote> quote = client().getQuote("AAPL");

        assertThatThrownBy(quote::block)
                .isInstanceOf(UpstreamApiException.class)
                .satisfies(ex -> {
                    UpstreamApiException upstreamEx = (UpstreamApiException) ex;
                    assertThat(upstreamEx.vendor()).isEqualTo("EODHD");
                    assertThat(upstreamEx.status().value()).isEqualTo(429);
                });
        assertThat(healthTracker.outcome("EODHD")).isEqualTo(VendorHealthTracker.Outcome.DOWN);
        assertThat(meterRegistry.counter("stockselect.vendor.calls", "vendor", "EODHD", "outcome", "failure").count())
                .isEqualTo(1.0);
    }

    @Test
    void mapsASlowVendorResponseToAGatewayTimeoutUpstreamExceptionAndRecordsFailure() {
        wireMock.stubFor(get(urlPathEqualTo("/api/real-time/AAPL"))
                .willReturn(okJson("{}").withFixedDelay(500)));

        EodhdProperties properties = new EodhdProperties(wireMock.baseUrl(), "test-token");
        WebClient webClient = WebClient.builder()
                .baseUrl(properties.baseUrl())
                .clientConnector(WebClientConfig.timeoutConnector(Duration.ofSeconds(5), Duration.ofMillis(100)))
                .build();
        EodhdClient client = new EodhdClient(webClient, properties, healthTracker, meterRegistry);

        assertThatThrownBy(() -> client.getQuote("AAPL").block())
                .isInstanceOf(UpstreamApiException.class)
                .satisfies(ex -> {
                    UpstreamApiException upstreamEx = (UpstreamApiException) ex;
                    assertThat(upstreamEx.vendor()).isEqualTo("EODHD");
                    assertThat(upstreamEx.status().value()).isEqualTo(504);
                });
        assertThat(healthTracker.outcome("EODHD")).isEqualTo(VendorHealthTracker.Outcome.DOWN);
        assertThat(meterRegistry.counter("stockselect.vendor.calls", "vendor", "EODHD", "outcome", "failure").count())
                .isEqualTo(1.0);
    }

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
}
