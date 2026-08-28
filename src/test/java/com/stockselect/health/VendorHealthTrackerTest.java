package com.stockselect.health;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VendorHealthTrackerTest {

    private final VendorHealthTracker tracker = new VendorHealthTracker();

    @Test
    void reportsUnknownForAVendorThatHasNeverBeenCalled() {
        assertThat(tracker.outcome("EODHD")).isEqualTo(VendorHealthTracker.Outcome.UNKNOWN);
        assertThat(tracker.detail("EODHD")).isNull();
        assertThat(tracker.checkedAt("EODHD")).isNull();
    }

    @Test
    void recordsSuccessAndClearsAnyPriorDetail() {
        tracker.recordFailure("EODHD", "500 Internal Server Error");

        tracker.recordSuccess("EODHD");

        assertThat(tracker.outcome("EODHD")).isEqualTo(VendorHealthTracker.Outcome.UP);
        assertThat(tracker.detail("EODHD")).isNull();
        assertThat(tracker.checkedAt("EODHD")).isNotNull();
    }

    @Test
    void recordsFailureWithDetail() {
        tracker.recordFailure("MarketData.app", "429 Too Many Requests");

        assertThat(tracker.outcome("MarketData.app")).isEqualTo(VendorHealthTracker.Outcome.DOWN);
        assertThat(tracker.detail("MarketData.app")).isEqualTo("429 Too Many Requests");
        assertThat(tracker.checkedAt("MarketData.app")).isNotNull();
    }

    @Test
    void tracksEachVendorIndependently() {
        tracker.recordSuccess("EODHD");
        tracker.recordFailure("MarketData.app", "403 Forbidden");

        assertThat(tracker.outcome("EODHD")).isEqualTo(VendorHealthTracker.Outcome.UP);
        assertThat(tracker.outcome("MarketData.app")).isEqualTo(VendorHealthTracker.Outcome.DOWN);
    }

    @Test
    void recordsRateLimitRemaining() {
        tracker.recordRateLimit("MarketData.app", 5);

        assertThat(tracker.rateLimitRemaining("MarketData.app")).isEqualTo(5);
    }

    @Test
    void preservesRateLimitAcrossSuccessRecords() {
        tracker.recordRateLimit("MarketData.app", 10);
        tracker.recordSuccess("MarketData.app");

        assertThat(tracker.rateLimitRemaining("MarketData.app")).isEqualTo(10);
        assertThat(tracker.outcome("MarketData.app")).isEqualTo(VendorHealthTracker.Outcome.UP);
    }

    @Test
    void rateLimitIsTrackedPerVendor() {
        tracker.recordRateLimit("EODHD", 20);
        tracker.recordRateLimit("MarketData.app", 5);

        assertThat(tracker.rateLimitRemaining("EODHD")).isEqualTo(20);
        assertThat(tracker.rateLimitRemaining("MarketData.app")).isEqualTo(5);
    }

    @Test
    void rateLimitRemainingIsNullBeforeRecorded() {
        assertThat(tracker.rateLimitRemaining("MarketData.app")).isNull();
    }
}
