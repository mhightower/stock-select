package com.stockselect;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the whole application context — the one thing the slice tests (@WebMvcTest) and pure
 * unit tests elsewhere never exercise. Catches bean-wiring failures and bad
 * @ConfigurationProperties bindings (e.g. a typo in application.yml's strategy.* keys) that
 * would otherwise only surface the first time someone actually runs the app. Needs no API keys
 * or network access: neither / nor /health makes a vendor call, and both EODHD/MarketData
 * properties have safe defaults (application.yml's ${EODHD_API_KEY:demo} / ${MARKETDATA_API_KEY:}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class StockSelectApplicationTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void contextLoadsAndServesTheRootEndpoint() {
        ResponseEntity<String> response = restTemplate.getForEntity("/", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("stock-select");
    }

    @Test
    void servesTheHealthEndpointWithoutMakingAnyVendorCalls() {
        ResponseEntity<String> response = restTemplate.getForEntity("/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UNKNOWN\"");
    }
}
