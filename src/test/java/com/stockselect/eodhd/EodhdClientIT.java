package com.stockselect.eodhd;

import com.stockselect.config.EodhdProperties;
import com.stockselect.config.WebClientConfig;
import com.stockselect.eodhd.dto.Quote;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hits the real EODHD API. Requires EODHD_API_KEY; skipped otherwise. Run via
 * {@code mvn verify} (Failsafe), not {@code mvn test} — this is not a unit test.
 */
@EnabledIfEnvironmentVariable(named = "EODHD_API_KEY", matches = ".+")
class EodhdClientIT {

    @Test
    void fetchesARealQuote() {
        EodhdProperties properties = new EodhdProperties("https://eodhd.com", System.getenv("EODHD_API_KEY"));
        WebClient webClient = new WebClientConfig().eodhdWebClient(properties);
        EodhdClient client = new EodhdClient(webClient, properties);

        Quote quote = client.getQuote("AAPL").block();

        assertThat(quote).isNotNull();
        assertThat(quote.code()).isEqualTo("AAPL.US");
        assertThat(quote.close()).isGreaterThan(0);
    }
}
