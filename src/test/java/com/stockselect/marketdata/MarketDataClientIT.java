package com.stockselect.marketdata;

import com.stockselect.config.MarketDataProperties;
import com.stockselect.config.WebClientConfig;
import com.stockselect.strategy.OptionContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hits the real MarketData.app API. Requires MARKETDATA_API_KEY; skipped otherwise. Run via
 * {@code mvn verify} (Failsafe), not {@code mvn test} — this is not a unit test.
 */
@EnabledIfEnvironmentVariable(named = "MARKETDATA_API_KEY", matches = ".+")
class MarketDataClientIT {

    @Test
    void fetchesARealOptionsChain() {
        MarketDataProperties properties = new MarketDataProperties(
                "https://api.marketdata.app", System.getenv("MARKETDATA_API_KEY"));
        WebClient webClient = new WebClientConfig().marketDataWebClient(properties);
        MarketDataClient client = new MarketDataClient(webClient);

        List<OptionContract> contracts = client.getOptionsChain("AAPL").collectList().block();

        assertThat(contracts).isNotEmpty();
        OptionContract first = contracts.get(0);
        assertThat(first.underlyingSymbol()).isEqualTo("AAPL");
        assertThat(first.strike()).isGreaterThan(0);
        assertThat(first.expirationDate()).isNotNull();
    }
}
