package com.stockselect.marketdata.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Response shape of {@code GET /v1/options/chain/{symbol}/}: a "parallel arrays" format where
 * every field is a list, and index {@code i} across all lists describes one contract.
 * {@code s} is "ok", "no_data", or "error".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OptionsChainResponse(
        String s,
        List<String> optionSymbol,
        List<String> underlying,
        List<Long> expiration,
        List<String> side,
        List<Double> strike,
        List<Integer> dte,
        List<Double> bid,
        List<Double> ask,
        List<Double> mid,
        List<Long> volume,
        List<Long> openInterest,
        List<Double> iv,
        List<Double> delta,
        List<Double> gamma,
        List<Double> theta,
        List<Double> vega
) {

    public boolean isOk() {
        return "ok".equals(s);
    }

    public int size() {
        return optionSymbol == null ? 0 : optionSymbol.size();
    }
}
