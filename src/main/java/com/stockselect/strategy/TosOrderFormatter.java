package com.stockselect.strategy;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Builds order-entry text in TOS order-bar syntax (VERTICAL/CUSTOM keywords, "D MMM YY"
 * dates, per-leg -1/+1 quantity signs) so a candidate can be pasted straight into a live TOS
 * session instead of re-entered leg by leg. Built from documented order-bar conventions, not
 * verified against a live TOS session — test-paste one before trusting it for a real order.
 */
public final class TosOrderFormatter {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("d MMM yy", Locale.US);

    private TosOrderFormatter() {
    }

    public static String putVertical(String symbol, LocalDate expiration, double shortStrike, double longStrike) {
        return "-1 VERTICAL " + symbol + " 100 " + date(expiration) + " "
                + strike(shortStrike) + "/" + strike(longStrike) + " PUT";
    }

    public static String callVertical(String symbol, LocalDate expiration, double shortStrike, double longStrike) {
        return "-1 VERTICAL " + symbol + " 100 " + date(expiration) + " "
                + strike(shortStrike) + "/" + strike(longStrike) + " CALL";
    }

    public static String customThreeLeg(String symbol, LocalDate expiration, double shortCallStrike,
            double shortPutStrike, double longPutStrike) {
        String d = date(expiration);
        return "CUSTOM " + symbol + " 100 -1 " + d + " " + strike(shortCallStrike) + " CALL, "
                + "-1 " + d + " " + strike(shortPutStrike) + " PUT, "
                + "+1 " + d + " " + strike(longPutStrike) + " PUT";
    }

    private static String date(LocalDate expiration) {
        return expiration.format(DATE_FORMAT).toUpperCase(Locale.US);
    }

    private static String strike(double value) {
        if (value == Math.floor(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }
}
