package com.stockselect.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "marketdata")
public record MarketDataProperties(String baseUrl, String apiKey) {
}
