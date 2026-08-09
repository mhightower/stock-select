package com.stockselect.strategy.jadelizard;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "strategy.jade-lizard")
public record JadeLizardProperties(
        int targetDte,
        int minDte,
        int maxDte,
        double shortCallTargetDelta,
        double shortPutTargetDelta,
        double minCreditToWidthRatio
) {
}
