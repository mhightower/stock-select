package com.stockselect.health;

import com.stockselect.eodhd.EodhdClient;
import com.stockselect.marketdata.MarketDataClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Both indicators are passive: they report whatever {@link VendorHealthTracker} last observed
 * during real traffic, never issuing their own vendor calls (see {@link VendorHealthTracker}).
 *
 * <p>EODHD failures use a custom {@code DEGRADED} status rather than {@code DOWN} — EODHD is
 * optional (see {@code ScreeningService}, which falls back to MarketData.app's own price), so an
 * EODHD outage alone should not flip the aggregate {@code /health} status to DOWN. MarketData.app
 * failures use the standard {@code DOWN} status since there's no candidate to build without it.
 */
@Configuration
public class HealthConfig {

    private static final Status DEGRADED = new Status("DEGRADED");

    @Bean
    public HealthIndicator eodhd(VendorHealthTracker tracker) {
        return () -> toHealth(tracker, EodhdClient.VENDOR, DEGRADED);
    }

    @Bean
    public HealthIndicator marketData(VendorHealthTracker tracker) {
        return () -> toHealth(tracker, MarketDataClient.VENDOR, Status.DOWN);
    }

    private static Health toHealth(VendorHealthTracker tracker, String vendor, Status downStatus) {
        VendorHealthTracker.Outcome outcome = tracker.outcome(vendor);
        Health.Builder builder = switch (outcome) {
            case UP -> Health.up();
            case DOWN -> Health.status(downStatus);
            case UNKNOWN -> Health.unknown().withDetail("note", "No " + vendor + " calls made yet this run");
        };
        if (tracker.checkedAt(vendor) != null) {
            builder.withDetail("lastChecked", tracker.checkedAt(vendor).toString());
        }
        if (tracker.detail(vendor) != null) {
            builder.withDetail("error", tracker.detail(vendor));
        }
        return builder.build();
    }
}
