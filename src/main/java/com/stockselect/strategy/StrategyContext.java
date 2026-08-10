package com.stockselect.strategy;

import java.util.List;

public record StrategyContext(String symbol, double underlyingPrice, List<OptionContract> optionsChain) {
}
