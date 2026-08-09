package com.stockselect.screening;

import com.stockselect.eodhd.EodhdClient;
import com.stockselect.strategy.StrategyContext;
import com.stockselect.strategy.TradeCandidate;
import com.stockselect.strategy.TradeStrategy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ScreeningService {

    private final EodhdClient eodhdClient;
    private final Map<String, TradeStrategy> strategiesByName;

    public ScreeningService(EodhdClient eodhdClient, List<TradeStrategy> strategies) {
        this.eodhdClient = eodhdClient;
        this.strategiesByName = strategies.stream()
                .collect(Collectors.toMap(TradeStrategy::name, Function.identity()));
    }

    public List<TradeCandidate> screen(String symbol, String strategyName) {
        TradeStrategy strategy = strategiesByName.get(strategyName);
        if (strategy == null) {
            throw new IllegalArgumentException("Unknown strategy: " + strategyName
                    + ". Available: " + strategiesByName.keySet());
        }

        String normalizedSymbol = symbol.toUpperCase();
        var quote = eodhdClient.getQuote(normalizedSymbol).block();
        var optionsChain = eodhdClient.getOptionsChain(normalizedSymbol).collectList().block();

        return strategy.evaluate(new StrategyContext(normalizedSymbol, quote, optionsChain));
    }
}
