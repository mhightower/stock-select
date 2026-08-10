package com.stockselect.screening;

import com.stockselect.UpstreamApiException;
import com.stockselect.eodhd.EodhdClient;
import com.stockselect.marketdata.MarketDataClient;
import com.stockselect.strategy.OptionContract;
import com.stockselect.strategy.StrategyContext;
import com.stockselect.strategy.TradeStrategy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ScreeningService {

    private final EodhdClient eodhdClient;
    private final MarketDataClient marketDataClient;
    private final Map<String, TradeStrategy> strategiesByName;

    public ScreeningService(EodhdClient eodhdClient, MarketDataClient marketDataClient, List<TradeStrategy> strategies) {
        this.eodhdClient = eodhdClient;
        this.marketDataClient = marketDataClient;
        this.strategiesByName = strategies.stream()
                .collect(Collectors.toMap(TradeStrategy::name, Function.identity()));
    }

    public ScreeningResult screen(String symbol, String strategyName) {
        TradeStrategy strategy = strategiesByName.get(strategyName);
        if (strategy == null) {
            throw new IllegalArgumentException("Unknown strategy: " + strategyName
                    + ". Available: " + strategiesByName.keySet());
        }

        // MarketData.app rejects exchange-suffixed symbols (e.g. "AAPL.US") outright; EODHD
        // accepts the bare ticker fine, so normalize to bare for both clients.
        String normalizedSymbol = symbol.toUpperCase().replaceFirst("\\.US$", "");
        List<OptionContract> optionsChain = marketDataClient.getOptionsChain(normalizedSymbol).collectList().block();

        List<String> warnings = new ArrayList<>();
        double underlyingPrice = resolveUnderlyingPrice(normalizedSymbol, optionsChain, warnings);

        StrategyContext context = new StrategyContext(normalizedSymbol, underlyingPrice, optionsChain);
        return new ScreeningResult(strategy.evaluate(context), warnings);
    }

    /**
     * EODHD's quote is only a "nicer" (near-real-time vs. MarketData's 24h-delayed) price than
     * what's already embedded in the option chain, not something the strategy strictly needs —
     * so a vendor failure here degrades to a warning instead of failing the whole request.
     */
    private double resolveUnderlyingPrice(String symbol, List<OptionContract> optionsChain, List<String> warnings) {
        try {
            return eodhdClient.getQuote(symbol).block().close();
        } catch (UpstreamApiException ex) {
            warnings.add("EODHD unavailable (" + ex.getMessage() + "); using MarketData.app's price instead.");
            return optionsChain.isEmpty() ? 0.0 : optionsChain.get(0).underlyingPrice();
        }
    }
}
