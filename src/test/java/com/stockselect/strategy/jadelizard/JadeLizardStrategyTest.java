package com.stockselect.strategy.jadelizard;

import com.stockselect.strategy.OptionContract;
import com.stockselect.strategy.StrategyContext;
import com.stockselect.strategy.TradeCandidate;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JadeLizardStrategyTest {

    private static final LocalDate EXPIRATION = LocalDate.now().plusDays(45);
    private static final double UNDERLYING_PRICE = 100.0;

    private final JadeLizardProperties properties = new JadeLizardProperties(
            45, 30, 60, 0.16, 0.16, 1.0, 10, 0.20);
    private final JadeLizardStrategy strategy = new JadeLizardStrategy(properties);

    @Test
    void picksLegsAndComputesCreditWhenRuleIsSatisfied() {
        List<OptionContract> chain = List.of(
                call(110, 0.16, 1.00, 1.10),
                call(105, 0.30, 2.00, 2.10),
                put(95, -0.16, 1.00, 1.10),
                put(94, -0.10, 0.55, 0.65),
                put(90, -0.04, 0.10, 0.15)
        );

        List<TradeCandidate> candidates = strategy.evaluate(new StrategyContext("AAPL.US", UNDERLYING_PRICE, chain));

        assertThat(candidates).hasSize(1);
        TradeCandidate candidate = candidates.get(0);
        assertThat(candidate.strategyName()).isEqualTo("jade-lizard");
        assertThat(candidate.currency()).isEqualTo("USD");
        assertThat(candidate.shortCallStrike()).isEqualTo(110.0);
        assertThat(candidate.longCallStrike()).isNull();
        assertThat(candidate.shortPutStrike()).isEqualTo(95.0);
        assertThat(candidate.longPutStrike()).isEqualTo(94.0);
        assertThat(candidate.definedRiskWidth()).isEqualTo(1.0);
        assertThat(candidate.creditReceived()).isEqualTo(1.5);
        assertThat(candidate.maxLoss()).isEqualTo(-0.5);
        assertThat(candidate.upsideBreakEven()).isEqualTo(111.5);
        assertThat(candidate.downsideBreakEven()).isEqualTo(93.5);
    }

    @Test
    void returnsNoCandidateWhenCreditDoesNotCoverPutSpreadWidth() {
        List<OptionContract> chain = List.of(
                call(110, 0.16, 1.00, 1.10),
                put(95, -0.16, 1.00, 1.10),
                put(90, -0.08, 0.43, 0.47)
        );

        List<TradeCandidate> candidates = strategy.evaluate(new StrategyContext("AAPL.US", UNDERLYING_PRICE, chain));

        assertThat(candidates).isEmpty();
    }

    @Test
    void returnsNoCandidateWhenNoExpirationIsInTheTargetDteWindow() {
        List<OptionContract> chain = List.of(
                call(110, 0.16, 1.00, 1.10, LocalDate.now().plusDays(5), 5),
                put(95, -0.16, 1.00, 1.10, LocalDate.now().plusDays(5), 5)
        );

        List<TradeCandidate> candidates = strategy.evaluate(new StrategyContext("AAPL.US", UNDERLYING_PRICE, chain));

        assertThat(candidates).isEmpty();
    }

    @Test
    void returnsNoCandidateWhenNoCallHasADelta() {
        List<OptionContract> chain = List.of(
                callNoDelta(110, 1.00, 1.10),
                put(95, -0.16, 1.00, 1.10),
                put(90, -0.08, 0.43, 0.47)
        );

        List<TradeCandidate> candidates = strategy.evaluate(new StrategyContext("AAPL.US", UNDERLYING_PRICE, chain));

        assertThat(candidates).isEmpty();
    }

    @Test
    void returnsNoCandidateWhenNoPutHasADelta() {
        List<OptionContract> chain = List.of(
                call(110, 0.16, 1.00, 1.10),
                putNoDelta(95, 1.00, 1.10),
                putNoDelta(90, 0.40, 0.50)
        );

        List<TradeCandidate> candidates = strategy.evaluate(new StrategyContext("AAPL.US", UNDERLYING_PRICE, chain));

        assertThat(candidates).isEmpty();
    }

    @Test
    void returnsNoCandidateWhenNoPutStrikeIsBelowTheShortPut() {
        List<OptionContract> chain = List.of(
                call(110, 0.16, 1.00, 1.10),
                put(95, -0.16, 1.00, 1.10)
        );

        List<TradeCandidate> candidates = strategy.evaluate(new StrategyContext("AAPL.US", UNDERLYING_PRICE, chain));

        assertThat(candidates).isEmpty();
    }

    @Test
    void returnsNoCandidateWhenTheOnlyLongPutHasTooWideABidAskSpread() {
        List<OptionContract> chain = List.of(
                call(110, 0.16, 1.00, 1.10),
                put(95, -0.16, 1.00, 1.10),
                // Perfect strike for the long put, but bid/ask of 0.05/0.50 is a 900% spread
                // relative to its mid — no realistic way to fill this leg at the priced credit.
                put(94, -0.10, 0.05, 0.50)
        );

        List<TradeCandidate> candidates = strategy.evaluate(new StrategyContext("AAPL.US", UNDERLYING_PRICE, chain));

        assertThat(candidates).isEmpty();
    }

    private static OptionContract call(double strike, double delta, double bid, double ask) {
        return call(strike, delta, bid, ask, EXPIRATION, 45);
    }

    private static OptionContract call(double strike, double delta, double bid, double ask,
                                        LocalDate expiration, int dte) {
        return contract(strike, "call", delta, bid, ask, expiration, dte);
    }

    private static OptionContract callNoDelta(double strike, double bid, double ask) {
        return contract(strike, "call", null, bid, ask, EXPIRATION, 45);
    }

    private static OptionContract put(double strike, double delta, double bid, double ask) {
        return put(strike, delta, bid, ask, EXPIRATION, 45);
    }

    private static OptionContract put(double strike, double delta, double bid, double ask,
                                       LocalDate expiration, int dte) {
        return contract(strike, "put", delta, bid, ask, expiration, dte);
    }

    private static OptionContract putNoDelta(double strike, double bid, double ask) {
        return contract(strike, "put", null, bid, ask, EXPIRATION, 45);
    }

    private static OptionContract contract(double strike, String type, Double delta, double bid, double ask,
                                            LocalDate expiration, int dte) {
        return new OptionContract(
                "AAPL" + expiration + type + strike,
                "AAPL.US",
                expiration,
                type,
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
