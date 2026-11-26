package com.fermapp.api.common.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Numeric helpers with exactly the JavaScript semantics the domain rules were written for.
 *
 * <p>{@code Math.round} in Java and JavaScript agree (half rounds towards positive infinity),
 * so {@link #round(double)} delegates to it. {@code Number.prototype.toFixed} uses the exact
 * binary value of the double with half-up rounding, which is what
 * {@code new BigDecimal(double).setScale(d, HALF_UP)} does.</p>
 */
public final class Js {

    private Js() {
    }

    /** {@code Math.round(value)} returned as a double, like JavaScript numbers. */
    public static double round(double value) {
        return (double) Math.round(value);
    }

    /** {@code Math.round(value * 10 ** digits) / 10 ** digits}. */
    public static double round(double value, int digits) {
        double factor = Math.pow(10, digits);
        return Math.round(value * factor) / factor;
    }

    /** {@code Math.round(value * 100) / 100}. */
    public static double round2(double value) {
        return Math.round(value * 100) / 100.0;
    }

    /** {@code Math.round(value * 10) / 10}. */
    public static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }

    /** {@code value.toFixed(digits)}. */
    public static String toFixed(double value, int digits) {
        return new BigDecimal(value).setScale(digits, RoundingMode.HALF_UP).toPlainString();
    }

    /** {@code String(number)} for the numbers the API prints in messages: 6 not 6.0, 2.5 stays 2.5. */
    public static String num(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (value == Math.rint(value) && !Double.isInfinite(value) && Math.abs(value) < 1e21) {
            return Long.toString((long) value);
        }
        String text = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        return text;
    }

    public static String num(Number value) {
        return value == null ? "null" : num(value.doubleValue());
    }

    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** {@code Number.isFinite}. */
    public static boolean finite(Double value) {
        return value != null && !value.isNaN() && !value.isInfinite();
    }
}
