package com.stockselect.health;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Records the outcome of each vendor call as it actually happens during real traffic, rather
 * than issuing dedicated health-check pings — EODHD's and MarketData.app's free tiers are
 * quota-limited (20/day and 100/day respectively), so a health check that makes its own vendor
 * calls on every poll would burn through those quotas on its own.
 */
@Component
public class VendorHealthTracker {

    public enum Outcome { UP, DOWN, UNKNOWN }

    private record Record(Outcome outcome, String detail, Instant checkedAt, Integer rateLimitRemaining) {
    }

    private final ConcurrentHashMap<String, AtomicReference<Record>> records = new ConcurrentHashMap<>();

    public void recordSuccess(String vendor) {
        recordFor(vendor).updateAndGet(r -> new Record(Outcome.UP, null, Instant.now(), r.rateLimitRemaining()));
    }

    public void recordFailure(String vendor, String detail) {
        recordFor(vendor).set(new Record(Outcome.DOWN, detail, Instant.now(), null));
    }

    public Outcome outcome(String vendor) {
        Record record = recordFor(vendor).get();
        return record.outcome();
    }

    public String detail(String vendor) {
        return recordFor(vendor).get().detail();
    }

    public Instant checkedAt(String vendor) {
        return recordFor(vendor).get().checkedAt();
    }

    public void recordRateLimit(String vendor, int remaining) {
        recordFor(vendor).updateAndGet(r -> new Record(r.outcome(), r.detail(), r.checkedAt(), remaining));
    }

    public Integer rateLimitRemaining(String vendor) {
        return recordFor(vendor).get().rateLimitRemaining();
    }

    private AtomicReference<Record> recordFor(String vendor) {
        return records.computeIfAbsent(vendor,
                v -> new AtomicReference<>(new Record(Outcome.UNKNOWN, null, null, null)));
    }
}
