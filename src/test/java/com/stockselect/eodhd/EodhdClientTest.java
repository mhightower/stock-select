package com.stockselect.eodhd;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.stockselect.config.EodhdProperties;
import com.stockselect.eodhd.dto.OptionContract;
import com.stockselect.eodhd.dto.Quote;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies EodhdClient parses EODHD's actual response shapes, against a WireMock server
 * standing in for eodhd.com.
 */
class EodhdClientTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private EodhdClient client() {
        EodhdProperties properties = new EodhdProperties(wireMock.baseUrl(), "test-token");
        WebClient webClient = WebClient.builder().baseUrl(properties.baseUrl()).build();
        return new EodhdClient(webClient, properties);
    }

    @Test
    void parsesQuoteResponse() {
        wireMock.stubFor(get(urlPathEqualTo("/api/real-time/AAPL.US"))
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

        Quote quote = client().getQuote("AAPL.US").block();

        assertThat(quote).isNotNull();
        assertThat(quote.code()).isEqualTo("AAPL.US");
        assertThat(quote.close()).isEqualTo(193.5);
        assertThat(quote.previousClose()).isEqualTo(191.0);
        assertThat(quote.changePercent()).isEqualTo(1.31);
    }

    @Test
    void parsesOptionsChainEnvelope() {
        wireMock.stubFor(get(urlPathEqualTo("/api/mp/unicornbay/options/eod"))
                .withQueryParam("filter[underlying_symbol]", equalTo("AAPL.US"))
                .withQueryParam("api_token", equalTo("test-token"))
                .willReturn(okJson("""
                        {
                          "meta": { "offset": 0, "limit": 1000, "total": 1 },
                          "data": [
                            {
                              "id": "AAPL251219C00200000-2025-12-19",
                              "type": "options-eod",
                              "attributes": {
                                "contract": "AAPL251219C00200000",
                                "underlying_symbol": "AAPL.US",
                                "exp_date": "2025-12-19",
                                "type": "call",
                                "strike": 200,
                                "bid": 5.1,
                                "ask": 5.4,
                                "volume": 120,
                                "open_interest": 3400,
                                "delta": 0.16,
                                "gamma": 0.02,
                                "theta": -0.05,
                                "vega": 0.12,
                                "volatility": 0.28,
                                "dte": 45,
                                "midpoint": 5.25
                              }
                            }
                          ]
                        }
                        """)));

        List<OptionContract> chain = client().getOptionsChain("AAPL.US").collectList().block();

        assertThat(chain).hasSize(1);
        OptionContract contract = chain.get(0);
        assertThat(contract.isCall()).isTrue();
        assertThat(contract.strike()).isEqualTo(200.0);
        assertThat(contract.delta()).isEqualTo(0.16);
        assertThat(contract.dte()).isEqualTo(45);
        assertThat(contract.effectiveMidPrice()).isEqualTo(5.25);
    }
}
