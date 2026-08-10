package com.stockselect.screening;

import com.stockselect.strategy.TradeCandidate;

import java.util.List;

public record ScreeningResult(List<TradeCandidate> candidates, List<String> warnings) {
}
