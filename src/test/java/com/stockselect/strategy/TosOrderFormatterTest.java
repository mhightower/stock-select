package com.stockselect.strategy;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class TosOrderFormatterTest {

    private static final LocalDate EXPIRATION = LocalDate.of(2026, 9, 18);

    @Test
    void formatsAPutVertical() {
        String text = TosOrderFormatter.putVertical("AAPL", EXPIRATION, 95, 93);

        assertThat(text).isEqualTo("-1 VERTICAL AAPL 100 18 SEP 26 95/93 PUT");
    }

    @Test
    void formatsACallVertical() {
        String text = TosOrderFormatter.callVertical("AAPL", EXPIRATION, 105, 110);

        assertThat(text).isEqualTo("-1 VERTICAL AAPL 100 18 SEP 26 105/110 CALL");
    }

    @Test
    void formatsACustomThreeLegCombo() {
        String text = TosOrderFormatter.customThreeLeg("AAPL", EXPIRATION, 110, 95, 93);

        assertThat(text).isEqualTo(
                "CUSTOM AAPL 100 -1 18 SEP 26 110 CALL, -1 18 SEP 26 95 PUT, +1 18 SEP 26 93 PUT");
    }

    @Test
    void formatsFractionalStrikesWithoutATrailingZero() {
        String text = TosOrderFormatter.putVertical("AAPL", EXPIRATION, 95.5, 93);

        assertThat(text).isEqualTo("-1 VERTICAL AAPL 100 18 SEP 26 95.5/93 PUT");
    }

    @Test
    void formatsWholeNumberStrikesWithoutADecimalPoint() {
        String text = TosOrderFormatter.callVertical("AAPL", EXPIRATION, 105.0, 110.0);

        assertThat(text).isEqualTo("-1 VERTICAL AAPL 100 18 SEP 26 105/110 CALL");
    }
}
