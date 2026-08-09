package com.stockselect.eodhd.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Top-level JSON:API-style envelope returned by the EODHD unicornbay options endpoints.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OptionsResponse(Meta meta, List<OptionData> data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Meta(int offset, int limit, int total) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OptionData(String id, String type, OptionContract attributes) {
    }
}
