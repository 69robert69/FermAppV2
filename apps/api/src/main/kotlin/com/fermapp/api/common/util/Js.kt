package com.fermapp.api.common.util

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Numeric helpers with exactly the JavaScript semantics the domain rules were written for.
 *
 * `Math.round` in Java and JavaScript agree (half rounds towards positive infinity), so
 * [round] delegates to it. `Number.prototype.toFixed` uses the exact binary value of the double
 * with half-up rounding, which is what `BigDecimal(double).setScale(d, HALF_UP)` does.
 */
object Js {

    /** `Math.round(value)` returned as a double, like JavaScript numbers. */
    @JvmStatic
    fun round(value: Double): Double = Math.round(value).toDouble()

    /** `Math.round(value * 10 ** digits) / 10 ** digits`. */
    @JvmStatic
    fun round(value: Double, digits: Int): Double {
        val factor = Math.pow(10.0, digits.toDouble())
        return Math.round(value * factor) / factor
    }

    /** `Math.round(value * 100) / 100`. */
    @JvmStatic
    fun round2(value: Double): Double = Math.round(value * 100) / 100.0

    /** `Math.round(value * 10) / 10`. */
    @JvmStatic
    fun round1(value: Double): Double = Math.round(value * 10) / 10.0

    /** `value.toFixed(digits)`. */
    @JvmStatic
    fun toFixed(value: Double, digits: Int): String = BigDecimal(value).setScale(digits, RoundingMode.HALF_UP).toPlainString()

    /**
     * `String(number)` for the numbers the API prints in messages: 6 not 6.0, 2.5 stays 2.5.
     * Infinity throws NumberFormatException, as it always has.
     */
    @JvmStatic
    fun num(value: Double): String {
        if (value.isNaN()) {
            return "NaN"
        }
        if (value == Math.rint(value) && !value.isInfinite() && Math.abs(value) < 1e21) {
            return value.toLong().toString()
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    }

    @JvmStatic
    fun num(value: Number?): String = if (value == null) "null" else num(value.toDouble())

    @JvmStatic
    fun clamp(value: Double, min: Double, max: Double): Double = Math.max(min, Math.min(max, value))

    /** `Number.isFinite`. */
    @JvmStatic
    fun finite(value: Double?): Boolean = value != null && value.isFinite()
}
