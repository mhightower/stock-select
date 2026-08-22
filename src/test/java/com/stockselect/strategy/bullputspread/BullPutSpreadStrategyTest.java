package com.stockselect.strategy.bullputspread;

import com.stockselect.strategy.OptionContract;
import com.stockselect.strategy.StrategyContext;
import com.stockselect.strategy.TosOrderFormatter;
import com.stockselect.strategy.TradeCandidate;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BullPutSpreadStrategyTest {

    private static final LocalDate EXPIRATION = LocalDate.now().plusDays(45);
    private static final double UNDERLYING_PRICE = 100.0;

    private final BullPutSpreadProperties properties = new BullPutSpreadProperties(
            45, 30, 60, 0.30, 0.33, 10, 0.20);
    private final BullPutSpreadStrategy strategy = new BullPutSpreadStrategy(properties);

    @Test
    void picksLegsAndComputesCreditWhenRuleIsSatisfied() {
        List<OptionContract> chain = List.of(
                put(95, -0.30, 1.00, 1.10),
                put(93, -0.12, 0.32, 0.38),
                put(80, -0.02, 0.05, 0.10)
        );

        List<TradeCandidate> candidates = strategy.evaluate(new StrategyContext("AAPL.US", UNDERLYING_PRICE, chain));

        assertThat(candidates).hasSize(1);
        TradeCandidate candidate = candidates.get(0);
        assertThat(candidate.strategyName()).isEqualTo("bull-put-spread");
        assertThat(candidate.currency()).isEqualTo("USD");
        assertThat(candidate.shortCallStrike()).isNull();
        assertThat(candidate.longCallStrike()).isNull();
        assertThat(candidate.shortCallDelta()).isNull();
        assertThat(candidate.shortPutStrike()).isEqualTo(95.0);
        assertThat(candidate.longPutStrike()).isEqualTo(93.0);
        assertThat(candidate.definedRiskWidth()).isEqualTo(2.0);
        assertThat(candidate.creditReceived()).isEqualTo(0.7);
        assertThat(candidate.maxLoss()).isEqualTo(1.3);
        assertThat(candidate.upsideBreakEven()).isNull();
        assertThat(candidate.downsideBreakEven()).isEqualTo(94.3);
        assertThat(candidate.tosOrderText()).isEqualTo(
                TosOrderFormatter.putVertical("AAPL.US", EXPIRATION, 95, 93));
    }

    @Test
    void returnsNoCandidateWhenCreditDoesNotCoverTheRequiredFractionOfWidth() {
        List<OptionContract> chain = List.of(
                put(95, -0.30, 1.00, 1.10),
                put(90, -0.05, 0.14, 0.16)
        );

        List<TradeCandidate> candidates = strategy.evaluate(new StrategyContext("AAPL.US", UNDERLYING_PRICE, chain));

        assertThat(candidates).isEmpty();
    }

    @Test
    void returnsNoCandidateWhenNoExpirationIsInTheTargetDteWindow() {
        List<OptionContract> chain = List.of(
                put(95, -0.30, 1.00, 1.10, LocalDate.now().plusDays(5), 5),
                put(93, -0.12, 0.30, 0.40, LocalDate.now().plusDays(5), 5)
        );

        List<TradeCandidate> candidates = strategy.evaluate(new StrategyContext("AAPL.US", UNDERLYING_PRICE, chain));

        assertThat(candidates).isEmpty();
    }

    @Test
    void returnsNoCandidateWhenNoPutHasADelta() {
        List<OptionContract> chain = List.of(
                putNoDelta(95, 1.00, 1.10),
                putNoDelta(93, 0.30, 0.40)
        );

        List<TradeCandidate> candidates = strategy.evaluate(new StrategyContext("AAPL.US", UNDERLYING_PRICE, chain));

        assertThat(candidates).isEmpty();
    }

    @Test
    void returnsNoCandidateWhenNoPutStrikeIsBelowTheShortPut() {
        List<OptionContract> chain = List.of(
                put(95, -0.30, 1.00, 1.10)
        );

        List<TradeCandidate> candidates = strategy.evaluate(new StrategyContext("AAPL.US", UNDERLYING_PRICE, chain));

        assertThat(candidates).isEmpty();
    }

    @Test
    void returnsNoCandidateWhenTheOnlyLongPutHasTooWideABidAskSpread() {
        List<OptionContract> chain = List.of(
                put(95, -0.30, 1.00, 1.10),
                // Perfect strike for the long put, but bid/ask of 0.05/0.50 is a 900% spread
                // relative to its mid — no realistic way to fill this leg at the priced credit.
                put(93, -0.12, 0.05, 0.50)
        );

        List<TradeCandidate> candidates = strategy.evaluate(new StrategyContext("AAPL.US", UNDERLYING_PRICE, chain));

        assertThat(candidates).isEmpty();
    }

    private static OptionContract put(double strike, double delta, double bid, double ask) {
        return put(strike, delta, bid, ask, EXPIRATION, 45);
    }

    private static OptionContract put(double strike, double delta, double bid, double ask,
                                       LocalDate expiration, int dte) {
        return contract(strike, delta, bid, ask, expiration, dte);
    }

    private static OptionContract putNoDelta(double strike, double bid, double ask) {
        return contract(strike, null, bid, ask, EXPIRATION, 45);
    }

    private static OptionContract contract(double strike, Double delta, double bid, double ask,
                                            LocalDate expiration, int dte) {
        return new OptionContract(
                "AAPL" + expiration + "put" + strike,
                "AAPL.US",
                expiration,
                "put",
                strike,
                UNDERLYING_PRICE,
                bid,
                ask,
                100L,
                500L,
                delta,
                0.05,
                -0.02,
                0.10,
                0.25,
                dte,
                (bid + ask) / 2.0
        );
    }
}
