package com.stockselect.strategy.jadelizard;

import com.stockselect.strategy.OptionContract;
import com.stockselect.strategy.StrategyContext;
import com.stockselect.strategy.TradeCandidate;
import com.stockselect.strategy.TradeStrategy;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Short call + short put vertical spread (short put / long further-OTM put), sized so that
 * total credit received is at least {@link JadeLizardProperties#minCreditToWidthRatio()} times
 * the put spread width.
 */
@Component
public class JadeLizardStrategy implements TradeStrategy {

    public static final String NAME = "jade-lizard";

    private final JadeLizardProperties properties;

    public JadeLizardStrategy(JadeLizardProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<TradeCandidate> evaluate(StrategyContext context) {
        Optional<LocalDate> expiration = pickExpiration(context.optionsChain());
        if (expiration.isEmpty()) {
            return List.of();
        }

        List<OptionContract> calls = contractsFor(context.optionsChain(), expiration.get(), true);
        List<OptionContract> puts = contractsFor(context.optionsChain(), expiration.get(), false);

        Optional<OptionContract> shortCall = closestByAbsoluteDelta(calls, properties.shortCallTargetDelta());
        Optional<OptionContract> shortPut = closestByAbsoluteDelta(puts, properties.shortPutTargetDelta());
        if (shortCall.isEmpty() || shortPut.isEmpty()) {
            return List.of();
        }

        Optional<OptionContract> longPut = puts.stream()
                .filter(put -> put.strike() < shortPut.get().strike())
                .max(Comparator.comparingDouble(OptionContract::strike));
        if (longPut.isEmpty()) {
            return List.of();
        }

        double credit = shortCall.get().effectiveMidPrice()
                + shortPut.get().effectiveMidPrice()
                - longPut.get().effectiveMidPrice();
        double width = shortPut.get().strike() - longPut.get().strike();
        if (credit < width * properties.minCreditToWidthRatio()) {
            return List.of();
        }

        TradeCandidate candidate = new TradeCandidate(
                NAME,
                context.symbol(),
                context.quote().close(),
                expiration.get(),
                shortCall.get().strike(),
                shortPut.get().strike(),
                longPut.get().strike(),
                shortCall.get().delta(),
                shortPut.get().delta(),
                round2(credit),
                round2(width),
                round2(width - credit),
                round2(shortCall.get().strike() + credit),
                round2(shortPut.get().strike() - credit)
        );
        return List.of(candidate);
    }

    private Optional<LocalDate> pickExpiration(List<OptionContract> chain) {
        return chain.stream()
                .filter(c -> c.dte() != null && c.dte() >= properties.minDte() && c.dte() <= properties.maxDte())
                .map(OptionContract::expirationDate)
                .distinct()
                .min(Comparator.comparingInt(date -> Math.abs(dteOf(chain, date) - properties.targetDte())));
    }

    private int dteOf(List<OptionContract> chain, LocalDate expiration) {
        return chain.stream()
                .filter(c -> expiration.equals(c.expirationDate()) && c.dte() != null)
                .findFirst()
                .map(OptionContract::dte)
                .orElse(Integer.MAX_VALUE);
    }

    private List<OptionContract> contractsFor(List<OptionContract> chain, LocalDate expiration, boolean calls) {
        return chain.stream()
                .filter(c -> expiration.equals(c.expirationDate()))
                .filter(c -> calls ? c.isCall() : c.isPut())
                .toList();
    }

    private Optional<OptionContract> closestByAbsoluteDelta(List<OptionContract> contracts, double targetAbsDelta) {
        return contracts.stream()
                .filter(c -> c.delta() != null)
                .min(Comparator.comparingDouble(c -> Math.abs(Math.abs(c.delta()) - targetAbsDelta)));
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
