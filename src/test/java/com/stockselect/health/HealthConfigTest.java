package com.stockselect.health;

import com.stockselect.eodhd.EodhdClient;
import com.stockselect.marketdata.MarketDataClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;

class HealthConfigTest {

    private final HealthConfig healthConfig = new HealthConfig();
    private final VendorHealthTracker tracker = new VendorHealthTracker();

    @Test
    void eodhdReportsUnknownBeforeAnyCallHasBeenMade() {
        Health health = healthConfig.eodhd(tracker).health();

        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails()).containsKey("note");
    }

    @Test
    void eodhdReportsUpAfterASuccessfulCall() {
        tracker.recordSuccess(EodhdClient.VENDOR);

        Health health = healthConfig.eodhd(tracker).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void eodhdReportsCustomDegradedStatusRatherThanDownAfterAFailure() {
        tracker.recordFailure(EodhdClient.VENDOR, "401 Unauthorized");

        Health health = healthConfig.eodhd(tracker).health();

        assertThat(health.getStatus()).isNotEqualTo(Status.DOWN);
        assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
        assertThat(health.getDetails()).containsEntry("error", "401 Unauthorized");
    }

    @Test
    void marketDataReportsDownAfterAFailureUnlikeEodhd() {
        tracker.recordFailure(MarketDataClient.VENDOR, "429 Too Many Requests");

        HealthIndicator marketDataIndicator = healthConfig.marketData(tracker);
        Health health = marketDataIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("error", "429 Too Many Requests");
    }

    @Test
    void marketDataReportsUpAfterASuccessfulCall() {
        tracker.recordSuccess(MarketDataClient.VENDOR);

        Health health = healthConfig.marketData(tracker).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void includesRateLimitRemainingInHealthDetails() {
        tracker.recordRateLimit(MarketDataClient.VENDOR, 3);
        tracker.recordSuccess(MarketDataClient.VENDOR);

        Health health = healthConfig.marketData(tracker).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("rateLimitRemaining", 3);
    }
}
