package com.stockselect.marketdata;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.stockselect.strategy.OptionContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies MarketDataClient parses MarketData.app's actual "parallel arrays" response shape,
 * against a WireMock server standing in for api.marketdata.app.
 */
class MarketDataClientTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private MarketDataClient client() {
        WebClient webClient = WebClient.builder()
                .baseUrl(wireMock.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .build();
        return new MarketDataClient(webClient);
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
        assertThat(call.dte()).isEqualTo(45);
        assertThat(call.delta()).isEqualTo(0.16);
        assertThat(call.effectiveMidPrice()).isEqualTo(5.25);
        assertThat(call.volatility()).isEqualTo(0.28);

        OptionContract put = contracts.get(1);
        assertThat(put.isPut()).isTrue();
        assertThat(put.strike()).isEqualTo(90.0);
        assertThat(put.delta()).isEqualTo(-0.16);
    }

    @Test
    void returnsNoContractsWhenTheResponseStatusIsNotOk() {
        wireMock.stubFor(get(urlPathEqualTo("/v1/options/chain/ZZZZ/"))
                .willReturn(okJson("""
                        { "s": "no_data", "errmsg": "Symbol not found." }
                        """)));

        List<OptionContract> contracts = client().getOptionsChain("ZZZZ").collectList().block();

        assertThat(contracts).isEmpty();
    }
}
