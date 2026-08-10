package com.stockselect.strategy;

import com.stockselect.eodhd.dto.Quote;

import java.util.List;

public record StrategyContext(String symbol, Quote quote, List<OptionContract> optionsChain) {
}
