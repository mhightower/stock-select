package com.stockselect.strategy;

import java.time.LocalDate;

/**
 * A concrete, priced trade suggestion produced by a {@link TradeStrategy}.
 * Legs that a given strategy doesn't use (e.g. longPutStrike for a strategy with no long put)
 * are left null. All prices are per-share (the options-market convention), not per-contract —
 * multiply by 100 for actual dollars on a standard equity contract. {@code currency} is always
 * "USD" (both vendors only cover US-listed equities/options) rather than read off either
 * vendor's response, since neither actually includes a currency field.
 */
public record TradeCandidate(
        String strategyName,
        String symbol,
        String currency,
        double underlyingPrice,
        LocalDate expirationDate,
        Double shortCallStrike,
        Double longCallStrike,
        Double shortPutStrike,
        Double longPutStrike,
        Double shortCallDelta,
        Double shortPutDelta,
        double creditReceived,
        Double definedRiskWidth,
        Double maxLoss,
        Double upsideBreakEven,
        Double downsideBreakEven
) {
}
