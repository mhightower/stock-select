package com.stockselect.strategy;

import java.util.List;

public interface TradeStrategy {

    /** Unique, lowercase-hyphenated identifier used to select this strategy, e.g. "jade-lizard". */
    String name();

    List<TradeCandidate> evaluate(StrategyContext context);
}
