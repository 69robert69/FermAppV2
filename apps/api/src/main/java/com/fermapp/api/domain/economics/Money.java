package com.fermapp.api.domain.economics;

import com.fermapp.api.common.util.Js;

/**
 * The farm works in euro (money.ts). Amounts are shown as €1.250 or €12.450,50:
 * dot for thousands, comma for decimals, the euro sign in front.
 */
public final class Money {

    public static final String CURRENCY = "EUR";
    public static final String CURRENCY_SYMBOL = "€";
    /** The rate used once to move the amounts recorded in lei to euro. */
    public static final double RON_PER_EUR = 4.97;

    private Money() {
    }

    private static String group(String integer) {
        return integer.replaceAll("\\B(?=(\\d{3})+(?!\\d))", ".");
    }

    /**
     * Formats an amount in euro. Whole amounts show no decimals unless asked;
     * {@code digits} forces a fixed number of decimals.
     */
    public static String formatEuro(Double value, Integer digits) {
        if (value == null || !Double.isFinite(value)) {
            return "-";
        }
        boolean negative = value < 0;
        double abs = Math.abs(value);
        int decimals = digits != null ? digits : (Math.abs(abs - Math.round(abs)) < 0.005 ? 0 : 2);
        String fixed = Js.toFixed(abs, decimals);
        String[] parts = fixed.split("\\.");
        return (negative ? "-" : "") + CURRENCY_SYMBOL + group(parts[0]) + (parts.length > 1 ? "," + parts[1] : "");
    }

    public static String formatEuro(Double value) {
        return formatEuro(value, null);
    }

    /** Amounts stored in another currency, read in euro. */
    public static double toEuro(double amount, String currency) {
        if (currency == null || currency.isEmpty() || CURRENCY.equalsIgnoreCase(currency)) {
            return amount;
        }
        if ("RON".equalsIgnoreCase(currency)) {
            return Js.round2(amount / RON_PER_EUR);
        }
        return amount;
    }
}
