package com.stockselect.eodhd.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response shape of {@code GET /api/real-time/{symbol}?fmt=json}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Quote(
        String code,
        long timestamp,
        double open,
        double high,
        double low,
        double close,
        long volume,
        @JsonProperty("previousClose") double previousClose,
        double change,
        @JsonProperty("change_p") double changePercent
) {
}
