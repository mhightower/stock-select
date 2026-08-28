package com.stockselect.screening;

import com.stockselect.UpstreamApiException;
import com.stockselect.eodhd.EodhdClient;
import com.stockselect.eodhd.dto.Quote;
import com.stockselect.marketdata.MarketDataClient;
import com.stockselect.strategy.OptionContract;
import com.stockselect.strategy.StrategyContext;
import com.stockselect.strategy.TradeStrategy;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ScreeningService {

    private static final Logger log = LoggerFactory.getLogger(ScreeningService.class);

    private final EodhdClient eodhdClient;
    private final MarketDataClient marketDataClient;
    private final Map<String, TradeStrategy> strategiesByName;
    private final MeterRegistry meterRegistry;

    public ScreeningService(EodhdClient eodhdClient, MarketDataClient marketDataClient, List<TradeStrategy> strategies,
            MeterRegistry meterRegistry) {
        this.eodhdClient = eodhdClient;
        this.marketDataClient = marketDataClient;
        this.strategiesByName = strategies.stream()
                .collect(Collectors.toMap(strategy -> strategy.name(), Function.identity()));
        this.meterRegistry = meterRegistry;
    }

    public ScreeningResult screen(String symbol, String strategyName) {
        TradeStrategy strategy = strategiesByName.get(strategyName);
        if (strategy == null) {
            // "unknown" sentinel, never the raw strategyName — an arbitrary user-supplied path
            // segment as a Prometheus tag value would be unbounded-cardinality label growth.
            meterRegistry.counter("stockselect.screen.requests", "strategy", "unknown", "outcome", "failure").increment();
            throw new IllegalArgumentException("Unknown strategy: " + strategyName
                    + ". Available: " + strategiesByName.keySet());
        }

        long startNanos = System.nanoTime();
        String outcome = "success";
        try {
            // MarketData.app rejects exchange-suffixed symbols (e.g. "AAPL.US") outright; EODHD
            // accepts the bare ticker fine, so normalize to bare for both clients.
            String normalizedSymbol = symbol.toUpperCase().replaceFirst("\\.US$", "");

            List<OptionContract> optionsChain;
            List<String> warnings = new ArrayList<>();
            double underlyingPrice;
            // The chain and quote calls are independent, so fetch them concurrently on their own
            // virtual threads instead of serially — halves the vendor latency on the happy path.
            try (var vthreads = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<List<OptionContract>> chainFuture =
                        vthreads.submit(() -> marketDataClient.getOptionsChain(normalizedSymbol).collectList().block());
                Future<Quote> quoteFuture = vthreads.submit(() -> eodhdClient.getQuote(normalizedSymbol).block());

                optionsChain = unwrap(chainFuture);
                underlyingPrice = resolveUnderlyingPrice(quoteFuture, optionsChain, warnings);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while screening " + normalizedSymbol, e);
            }

            StrategyContext context = new StrategyContext(normalizedSymbol, underlyingPrice, optionsChain);
            return new ScreeningResult(strategy.evaluate(context), warnings);
        } catch (RuntimeException ex) {
            outcome = "failure";
            throw ex;
        } finally {
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
            meterRegistry.counter("stockselect.screen.requests", "strategy", strategyName, "outcome", outcome).increment();
            Timer.builder("stockselect.screen.latency")
                    .tag("strategy", strategyName)
                    .tag("outcome", outcome)
                    .register(meterRegistry)
                    .record(elapsed);
            log.atInfo()
                    .addKeyValue("strategy", strategyName)
                    .addKeyValue("symbol", symbol)
                    .addKeyValue("status", outcome)
                    .addKeyValue("latencyMs", elapsed.toMillis())
                    .log("screen completed");
        }
    }

    /**
     * EODHD's quote is only a "nicer" (near-real-time vs. MarketData's 24h-delayed) price than
     * what's already embedded in the option chain, not something the strategy strictly needs —
     * so a vendor failure here degrades to a warning instead of failing the whole request.
     */
    private double resolveUnderlyingPrice(Future<Quote> quoteFuture, List<OptionContract> optionsChain,
            List<String> warnings) throws InterruptedException {
        try {
            return unwrap(quoteFuture).close();
        } catch (UpstreamApiException ex) {
            warnings.add("EODHD unavailable (" + ex.getMessage() + "); using MarketData.app's price instead.");
            return optionsChain.isEmpty() ? 0.0 : optionsChain.get(0).underlyingPrice();
        }
    }

    /** Unwraps a {@link Future}'s {@link ExecutionException} so the original vendor exception's
     * type is preserved for {@code ApiExceptionHandler} to match on. */
    private static <T> T unwrap(Future<T> future) throws InterruptedException {
        try {
            return future.get();
        } catch (ExecutionException e) {
            switch (e.getCause()) {
                case RuntimeException runtimeException -> throw runtimeException;
                case Error error -> throw error;
                default -> throw new IllegalStateException(e.getCause());
            }
        }
    }
}
