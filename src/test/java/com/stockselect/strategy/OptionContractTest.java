package com.stockselect.strategy;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class OptionContractTest {

    private static final LocalDate EXPIRATION = LocalDate.of(2026, 12, 18);

    @Test
    void effectiveMidPriceAveragesBidAndAskWhenBothPresent() {
        OptionContract contract = contract(5.0, 5.4, 5.1);

        assertThat(contract.effectiveMidPrice()).isEqualTo(5.2);
    }

    @Test
    void effectiveMidPriceFallsBackToMidpointWhenBidOrAskIsMissing() {
        OptionContract contract = contract(null, 5.4, 5.25);

        assertThat(contract.effectiveMidPrice()).isEqualTo(5.25);
    }

    @Test
    void effectiveMidPriceFallsBackToZeroWhenNothingIsAvailable() {
        OptionContract contract = contract(null, null, null);

        assertThat(contract.effectiveMidPrice()).isEqualTo(0.0);
    }

    @Test
    void isCallAndIsPutReflectTheTypeField() {
        assertThat(contract("call", 5.0, 5.4, 5.1).isCall()).isTrue();
        assertThat(contract("call", 5.0, 5.4, 5.1).isPut()).isFalse();
        assertThat(contract("put", 5.0, 5.4, 5.1).isCall()).isFalse();
        assertThat(contract("put", 5.0, 5.4, 5.1).isPut()).isTrue();
    }

    private static OptionContract contract(Double bid, Double ask, Double midpoint) {
        return contract("call", bid, ask, midpoint);
    }

    private static OptionContract contract(String type, Double bid, Double ask, Double midpoint) {
        return new OptionContract(
                "AAPL261218C00200000",
                "AAPL.US",
                EXPIRATION,
                type,
                200.0,
                bid,
                ask,
                100L,
                500L,
                0.16,
                0.05,
                -0.02,
                0.10,
                0.25,
                45,
                midpoint
        );
    }
}
