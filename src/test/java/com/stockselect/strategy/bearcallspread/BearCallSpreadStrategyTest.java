package com.stockselect.strategy.bearcallspread;

import com.stockselect.strategy.OptionContract;
import com.stockselect.strategy.StrategyContext;
import com.stockselect.strategy.TradeCandidate;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BearCallSpreadStrategyTest {

    private static final LocalDate EXPIRATION = LocalDate.now().plusDays(45);
    private static final double UNDERLYING_PRICE = 100.0;

    private final BearCallSpreadProperties properties = new BearCallSpreadProperties(
            45, 30, 60, 0.30, 0.33, 10, 0.20);
    private final BearCallSpreadStrategy strategy = new BearCallSpreadStrategy(properties);

    @Test
    void picksLegsAndComputesCreditWhenRuleIsSatisfied() {
        List<OptionContract> chain = List.of(
                call(105, 0.30, 1.00, 1.10),
                call(107, 0.12, 0.32, 0.38),
                call(120, 0.02, 0.05, 0.10)
        );

        List<TradeCandidate> candidates = strategy.evaluate(new StrategyContext("AAPL.US", UNDERLYING_PRICE, chain));

        assertThat(candidates).hasSize(1);
        TradeCandidate candidate = candidates.get(0);
        assertThat(candidate.strategyName()).isEqualTo("bear-call-spread");
        assertThat(candidate.currency()).isEqualTo("USD");
        assertThat(candidate.shortPutStrike()).isNull();
        assertThat(candidate.shortPutDelta()).isNull();
        assertThat(candidate.shortCallStrike()).isEqualTo(105.0);
        assertThat(candidate.longCallStrike()).isEqualTo(107.0);
        assertThat(candidate.definedRiskWidth()).isEqualTo(2.0);
        assertThat(candidate.creditReceived()).isEqualTo(0.7);
        assertThat(candidate.maxLoss()).isEqualTo(1.3);
        assertThat(candidate.downsideBreakEven()).isNull();
        assertThat(candidate.upsideBreakEven()).isEqualTo(105.7);
    }

    @Test
    void returnsNoCandidateWhenCreditDoesNotCoverTheRequiredFractionOfWidth() {
        List<OptionContract> chain = List.of(
                call(105, 0.30, 1.00, 1.10),
                call(110, 0.05, 0.14, 0.16)
        );

        List<TradeCandidate> candidates = strategy.evaluate(new StrategyContext("AAPL.US", UNDERLYING_PRICE, chain));

        assertThat(candidates).isEmpty();
    }

    @Test
    void returnsNoCandidateWhenNoExpirationIsInTheTargetDteWindow() {
        List<OptionContract> chain = List.of(
                call(105, 0.30, 1.00, 1.10, LocalDate.now().plusDays(5), 5),
                call(107, 0.12, 0.30, 0.40, LocalDate.now().plusDays(5), 5)
        );

        List<TradeCandidate> candidates = strategy.evaluate(new StrategyContext("AAPL.US", UNDERLYING_PRICE, chain));

        assertThat(candidates).isEmpty();
    }

    @Test
    void returnsNoCandidateWhenNoCallHasADelta() {
        List<OptionContract> chain = List.of(
                callNoDelta(105, 1.00, 1.10),
                callNoDelta(107, 0.30, 0.40)
        );

        List<TradeCandidate> candidates = strategy.evaluate(new StrategyContext("AAPL.US", UNDERLYING_PRICE, chain));

        assertThat(candidates).isEmpty();
    }

    @Test
    void returnsNoCandidateWhenNoCallStrikeIsAboveTheShortCall() {
        List<OptionContract> chain = List.of(
                call(105, 0.30, 1.00, 1.10)
        );

        List<TradeCandidate> candidates = strategy.evaluate(new StrategyContext("AAPL.US", UNDERLYING_PRICE, chain));

        assertThat(candidates).isEmpty();
    }

    @Test
    void returnsNoCandidateWhenTheOnlyLongCallHasTooWideABidAskSpread() {
        List<OptionContract> chain = List.of(
                call(105, 0.30, 1.00, 1.10),
                // Perfect strike for the long call, but bid/ask of 0.05/0.50 is a 900% spread
                // relative to its mid — no realistic way to fill this leg at the priced credit.
                call(107, 0.12, 0.05, 0.50)
        );

        List<TradeCandidate> candidates = strategy.evaluate(new StrategyContext("AAPL.US", UNDERLYING_PRICE, chain));

        assertThat(candidates).isEmpty();
    }

    private static OptionContract call(double strike, double delta, double bid, double ask) {
        return call(strike, delta, bid, ask, EXPIRATION, 45);
    }

    private static OptionContract call(double strike, double delta, double bid, double ask,
                                        LocalDate expiration, int dte) {
        return contract(strike, delta, bid, ask, expiration, dte);
    }

    private static OptionContract callNoDelta(double strike, double bid, double ask) {
        return contract(strike, null, bid, ask, EXPIRATION, 45);
    }

    private static OptionContract contract(double strike, Double delta, double bid, double ask,
                                            LocalDate expiration, int dte) {
        return new OptionContract(
                "AAPL" + expiration + "call" + strike,
                "AAPL.US",
                expiration,
                "call",
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
