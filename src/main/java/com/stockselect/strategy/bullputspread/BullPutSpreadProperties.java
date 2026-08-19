package com.stockselect.strategy.bullputspread;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "strategy.bull-put-spread")
public record BullPutSpreadProperties(
        int targetDte,
        int minDte,
        int maxDte,
        double shortPutTargetDelta,
        double minCreditToWidthRatio,
        long minOpenInterest,
        double maxBidAskSpreadRatio
) {
}
