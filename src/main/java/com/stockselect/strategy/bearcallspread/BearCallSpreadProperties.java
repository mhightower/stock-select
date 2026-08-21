package com.stockselect.strategy.bearcallspread;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "strategy.bear-call-spread")
public record BearCallSpreadProperties(
        int targetDte,
        int minDte,
        int maxDte,
        double shortCallTargetDelta,
        double minCreditToWidthRatio,
        long minOpenInterest,
        double maxBidAskSpreadRatio
) {
}
