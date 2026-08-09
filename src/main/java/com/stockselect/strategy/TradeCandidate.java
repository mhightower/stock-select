package com.stockselect.strategy;

import java.time.LocalDate;

/**
 * A concrete, priced trade suggestion produced by a {@link TradeStrategy}.
 * Legs that a given strategy doesn't use (e.g. longPutStrike for a strategy with no long put)
 * are left null.
 */
public record TradeCandidate(
        String strategyName,
        String symbol,
        double underlyingPrice,
        LocalDate expirationDate,
        Double shortCallStrike,
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
