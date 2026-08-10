package com.stockselect.strategy;

import java.time.LocalDate;

/**
 * A single option contract, in the app's own vendor-neutral shape. Data source clients
 * (e.g. {@code MarketDataClient}) map their wire format into this before it reaches any strategy.
 */
public record OptionContract(
        String contract,
        String underlyingSymbol,
        LocalDate expirationDate,
        String type,
        double strike,
        double underlyingPrice,
        Double bid,
        Double ask,
        Long volume,
        Long openInterest,
        Double delta,
        Double gamma,
        Double theta,
        Double vega,
        Double volatility,
        Integer dte,
        Double midpoint
) {

    public boolean isCall() {
        return "call".equalsIgnoreCase(type);
    }

    public boolean isPut() {
        return "put".equalsIgnoreCase(type);
    }

    /** Mid-price between bid and ask, falling back to the reported midpoint. */
    public double effectiveMidPrice() {
        if (bid != null && ask != null) {
            return (bid + ask) / 2.0;
        }
        return midpoint != null ? midpoint : 0.0;
    }
}
