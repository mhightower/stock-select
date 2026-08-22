package com.stockselect.strategy.bullputspread;

import com.stockselect.strategy.OptionContract;
import com.stockselect.strategy.StrategyContext;
import com.stockselect.strategy.TosOrderFormatter;
import com.stockselect.strategy.TradeCandidate;
import com.stockselect.strategy.TradeStrategy;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Short put vertical spread (short put / long further-OTM put) — no call leg at all. Sized so
 * that credit received is at least {@link BullPutSpreadProperties#minCreditToWidthRatio()} times
 * the spread width, the standard vertical-spread construction guideline. Only considers contracts
 * that clear a minimum liquidity bar (open interest and bid-ask spread width) — see
 * {@link #isLiquid}.
 */
@Component
public class BullPutSpreadStrategy implements TradeStrategy {

    public static final String NAME = "bull-put-spread";

    private final BullPutSpreadProperties properties;

    public BullPutSpreadStrategy(BullPutSpreadProperties properties) {
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

        List<OptionContract> puts = putsFor(context.optionsChain(), expiration.get());

        Optional<OptionContract> shortPut = closestByAbsoluteDelta(puts, properties.shortPutTargetDelta());
        if (shortPut.isEmpty()) {
            return List.of();
        }

        Optional<OptionContract> longPut = puts.stream()
                .filter(put -> put.strike() < shortPut.get().strike())
                .max(Comparator.comparingDouble(contract -> contract.strike()));
        if (longPut.isEmpty()) {
            return List.of();
        }

        double credit = shortPut.get().effectiveMidPrice() - longPut.get().effectiveMidPrice();
        double width = shortPut.get().strike() - longPut.get().strike();
        if (credit < width * properties.minCreditToWidthRatio()) {
            return List.of();
        }

        TradeCandidate candidate = new TradeCandidate(
                NAME,
                context.symbol(),
                "USD",
                context.underlyingPrice(),
                expiration.get(),
                null,
                null,
                shortPut.get().strike(),
                longPut.get().strike(),
                null,
                shortPut.get().delta(),
                round2(credit),
                round2(width),
                round2(width - credit),
                null,
                round2(shortPut.get().strike() - credit),
                TosOrderFormatter.putVertical(context.symbol(), expiration.get(),
                        shortPut.get().strike(), longPut.get().strike())
        );
        return List.of(candidate);
    }

    private Optional<LocalDate> pickExpiration(List<OptionContract> chain) {
        return chain.stream()
                .filter(c -> c.dte() != null && c.dte() >= properties.minDte() && c.dte() <= properties.maxDte())
                .map(contract -> contract.expirationDate())
                .distinct()
                .min(Comparator.comparingInt(date -> Math.abs(dteOf(chain, date) - properties.targetDte())));
    }

    private int dteOf(List<OptionContract> chain, LocalDate expiration) {
        return chain.stream()
                .filter(c -> expiration.equals(c.expirationDate()) && c.dte() != null)
                .findFirst()
                .map(contract -> contract.dte())
                .orElse(Integer.MAX_VALUE);
    }

    private List<OptionContract> putsFor(List<OptionContract> chain, LocalDate expiration) {
        return chain.stream()
                .filter(c -> expiration.equals(c.expirationDate()))
                .filter(contract -> contract.isPut())
                .filter(this::isLiquid)
                .toList();
    }

    /**
     * Rejects contracts with no real market to trade into: too little open interest, or a
     * bid-ask spread too wide relative to the mid to fill at a fair price.
     */
    private boolean isLiquid(OptionContract contract) {
        long openInterest = contract.openInterest() != null ? contract.openInterest() : 0;
        if (openInterest < properties.minOpenInterest()) {
            return false;
        }
        if (contract.bid() == null || contract.ask() == null) {
            return false;
        }
        double mid = contract.effectiveMidPrice();
        if (mid <= 0) {
            return false;
        }
        double spreadRatio = (contract.ask() - contract.bid()) / mid;
        return spreadRatio <= properties.maxBidAskSpreadRatio();
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
