package com.stockselect.eodhd.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;

/**
 * A single option contract's attributes, as returned by
 * {@code GET /api/mp/unicornbay/options/eod}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OptionContract(
        String contract,
        @JsonProperty("underlying_symbol") String underlyingSymbol,
        @JsonProperty("exp_date") LocalDate expirationDate,
        String type,
        double strike,
        Double bid,
        Double ask,
        Long volume,
        @JsonProperty("open_interest") Long openInterest,
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
