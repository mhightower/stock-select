package com.stockselect.marketdata;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.stockselect.UpstreamApiException;
import com.stockselect.config.WebClientConfig;
import com.stockselect.health.VendorHealthTracker;
import com.stockselect.strategy.OptionContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies MarketDataClient parses MarketData.app's actual "parallel arrays" response shape,
 * against a WireMock server standing in for api.marketdata.app.
 */
class MarketDataClientTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private final VendorHealthTracker healthTracker = new VendorHealthTracker();

    private MarketDataClient client() {
        WebClient webClient = WebClient.builder()
                .baseUrl(wireMock.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .build();
        return new MarketDataClient(webClient, healthTracker);
    }

    @Test
    void parsesTheParallelArrayChainResponse() {
        wireMock.stubFor(get(urlPathEqualTo("/v1/options/chain/AAPL/"))
                .withHeader("Authorization", equalTo("Bearer test-token"))
                .willReturn(okJson("""
                        {
                          "s": "ok",
                          "optionSymbol": ["AAPL260918C00110000", "AAPL260918P00090000"],
                          "underlying": ["AAPL", "AAPL"],
                          "expiration": [1789156800, 1789156800],
                          "side": ["call", "put"],
                          "strike": [110, 90],
                          "underlyingPrice": [100.5, 100.5],
                          "dte": [45, 45],
                          "bid": [5.10, 1.00],
                          "ask": [5.40, 1.10],
                          "mid": [5.25, 1.05],
                          "volume": [120, 80],
                          "openInterest": [3400, 2100],
                          "iv": [0.28, 0.31],
                          "delta": [0.16, -0.16],
                          "gamma": [0.02, 0.03],
                          "theta": [-0.05, -0.04],
                          "vega": [0.12, 0.10]
                        }
                        """)));

        List<OptionContract> contracts = client().getOptionsChain("AAPL").collectList().block();

        assertThat(contracts).hasSize(2);

        OptionContract call = contracts.get(0);
        assertThat(call.contract()).isEqualTo("AAPL260918C00110000");
        assertThat(call.underlyingSymbol()).isEqualTo("AAPL");
        assertThat(call.expirationDate()).isEqualTo(LocalDate.of(2026, 9, 11));
        assertThat(call.isCall()).isTrue();
        assertThat(call.strike()).isEqualTo(110.0);
        assertThat(call.underlyingPrice()).isEqualTo(100.5);
        assertThat(call.dte()).isEqualTo(45);
        assertThat(call.delta()).isEqualTo(0.16);
        assertThat(call.effectiveMidPrice()).isEqualTo(5.25);
        assertThat(call.volatility()).isEqualTo(0.28);

        OptionContract put = contracts.get(1);
        assertThat(put.isPut()).isTrue();
        assertThat(put.strike()).isEqualTo(90.0);
        assertThat(put.delta()).isEqualTo(-0.16);
        assertThat(healthTracker.outcome("MarketData.app")).isEqualTo(VendorHealthTracker.Outcome.UP);
    }

    @Test
    void requestsOnlyThe25To65DteWindowSharedByAllStrategies() {
        DateTimeFormatter isoDate = DateTimeFormatter.ISO_LOCAL_DATE;
        String expectedFrom = LocalDate.now().plusDays(25).format(isoDate);
        String expectedTo = LocalDate.now().plusDays(65).format(isoDate);

        wireMock.stubFor(get(urlPathEqualTo("/v1/options/chain/AAPL/"))
                .withQueryParam("from", equalTo(expectedFrom))
                .withQueryParam("to", equalTo(expectedTo))
                .willReturn(okJson("""
                        { "s": "no_data", "errmsg": "Symbol not found." }
                        """)));

        client().getOptionsChain("AAPL").collectList().block();

        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/v1/options/chain/AAPL/")));
    }

    @Test
    void returnsNoContractsWhenTheResponseStatusIsNotOkButStillRecordsVendorSuccess() {
        wireMock.stubFor(get(urlPathEqualTo("/v1/options/chain/ZZZZ/"))
                .willReturn(okJson("""
                        { "s": "no_data", "errmsg": "Symbol not found." }
                        """)));

        List<OptionContract> contracts = client().getOptionsChain("ZZZZ").collectList().block();

        assertThat(contracts).isEmpty();
        // A "no data for this symbol" business response is still a healthy vendor connection.
        assertThat(healthTracker.outcome("MarketData.app")).isEqualTo(VendorHealthTracker.Outcome.UP);
    }

    @Test
    void wrapsAVendorErrorResponseInAnUpstreamApiExceptionAndRecordsFailure() {
        wireMock.stubFor(get(urlPathEqualTo("/v1/options/chain/AAPL/"))
                .willReturn(aResponse().withStatus(429).withBody("Too Many Requests")));

        Flux<OptionContract> contracts = client().getOptionsChain("AAPL");

        assertThatThrownBy(contracts::blockLast)
                .isInstanceOf(UpstreamApiException.class)
                .satisfies(ex -> {
                    UpstreamApiException upstreamEx = (UpstreamApiException) ex;
                    assertThat(upstreamEx.vendor()).isEqualTo("MarketData.app");
                    assertThat(upstreamEx.status().value()).isEqualTo(429);
                });
        assertThat(healthTracker.outcome("MarketData.app")).isEqualTo(VendorHealthTracker.Outcome.DOWN);
    }

    @Test
    void extractsAndRecordsRateLimitRemainingFromResponseHeader() {
        // Rate limit headers are captured via ExchangeFilterFunction regardless of response status
        wireMock.stubFor(get(urlPathEqualTo("/v1/options/chain/AAPL/"))
                .withHeader("Authorization", equalTo("Bearer test-token"))
                .willReturn(okJson("""
                        {
                          "s": "ok",
                          "optionSymbol": ["AAPL260918C00110000"],
                          "underlying": ["AAPL"],
                          "expiration": [1789156800],
                          "side": ["call"],
                          "strike": [110],
                          "underlyingPrice": [100.5],
                          "dte": [45],
                          "bid": [5.10],
                          "ask": [5.40],
                          "mid": [5.25],
                          "volume": [120],
                          "openInterest": [3400],
                          "iv": [0.28],
                          "delta": [0.16],
                          "gamma": [0.02],
                          "theta": [-0.05],
                          "vega": [0.12]
                        }
                        """)
                        .withHeader("x-api-ratelimit-remaining", "7")));

        client().getOptionsChain("AAPL").blockLast();

        assertThat(healthTracker.rateLimitRemaining("MarketData.app")).isEqualTo(7);
    }

    @Test
    void mapsASlowVendorResponseToAGatewayTimeoutUpstreamExceptionAndRecordsFailure() {
        wireMock.stubFor(get(urlPathEqualTo("/v1/options/chain/AAPL/"))
                .willReturn(okJson("""
                        { "s": "ok" }
                        """).withFixedDelay(500)));

        WebClient webClient = WebClient.builder()
                .baseUrl(wireMock.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .clientConnector(WebClientConfig.timeoutConnector(Duration.ofSeconds(5), Duration.ofMillis(100)))
                .build();
        MarketDataClient client = new MarketDataClient(webClient, healthTracker);

        Flux<OptionContract> contracts = client.getOptionsChain("AAPL");

        assertThatThrownBy(contracts::blockLast)
                .isInstanceOf(UpstreamApiException.class)
                .satisfies(ex -> {
                    UpstreamApiException upstreamEx = (UpstreamApiException) ex;
                    assertThat(upstreamEx.vendor()).isEqualTo("MarketData.app");
                    assertThat(upstreamEx.status().value()).isEqualTo(504);
                });
        assertThat(healthTracker.outcome("MarketData.app")).isEqualTo(VendorHealthTracker.Outcome.DOWN);
    }
}
