package com.fermapp.api.domain.economics

import com.fermapp.api.common.util.Js

/**
 * The farm works in euro (money.ts). Amounts are shown as €1.250 or €12.450,50:
 * dot for thousands, comma for decimals, the euro sign in front.
 */
object Money {

    const val CURRENCY = "EUR"
    const val CURRENCY_SYMBOL = "€"

    /** The rate used once to move the amounts recorded in lei to euro. */
    const val RON_PER_EUR = 4.97

    private val THOUSANDS = Regex("\\B(?=(\\d{3})+(?!\\d))")

    private fun group(integer: String): String = integer.replace(THOUSANDS, ".")

    /**
     * Formats an amount in euro. Whole amounts show no decimals unless asked;
     * [digits] forces a fixed number of decimals.
     */
    @JvmStatic
    @JvmOverloads
    fun formatEuro(value: Double?, digits: Int? = null): String {
        if (value == null || !value.isFinite()) {
            return "-"
        }
        val abs = Math.abs(value)
        val decimals = digits ?: if (Math.abs(abs - Math.round(abs)) < 0.005) 0 else 2
        val parts = Js.toFixed(abs, decimals).split(".")
        val sign = if (value < 0) "-" else ""
        val fraction = if (parts.size > 1) "," + parts[1] else ""
        return sign + CURRENCY_SYMBOL + group(parts[0]) + fraction
    }

    /** Amounts stored in another currency, read in euro. */
    @JvmStatic
    fun toEuro(amount: Double, currency: String?): Double = when {
        currency.isNullOrEmpty() || CURRENCY.equals(currency, ignoreCase = true) -> amount
        "RON".equals(currency, ignoreCase = true) -> Js.round2(amount / RON_PER_EUR)
        else -> amount
    }
}
