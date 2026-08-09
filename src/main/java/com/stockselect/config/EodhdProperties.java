package com.stockselect.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "eodhd")
public record EodhdProperties(String baseUrl, String apiKey) {
}
