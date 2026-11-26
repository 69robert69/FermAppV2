package ro.fermapp.domain

import kotlin.math.abs

/*
 * The farm works in euro. Amounts are shown as €1.250 or €12.450,50:
 * dot for thousands, comma for decimals, the euro sign in front.
 */

const val CURRENCY = "EUR"
const val CURRENCY_SYMBOL = "€"

/** The rate used once to move the amounts recorded in lei to euro. */
const val RON_PER_EUR = 4.97

private fun group(integer: String): String =
    integer.reversed().chunked(3).joinToString(".").reversed()

/**
 * Formats an amount in euro. Whole amounts show no decimals unless asked;
 * [digits] forces a fixed number of decimals (for example cost per hectare).
 */
fun formatEuro(value: Double?, digits: Int? = null): String {
    if (value == null || !value.isFinite()) return "-"
    val negative = value < 0
    val absValue = abs(value)
    val decimals = digits ?: if (abs(absValue - jsRound(absValue)) < 0.005) 0 else 2
    val fixed = absValue.toFixed(decimals)
    val integer = fixed.substringBefore('.')
    val fraction = fixed.substringAfter('.', "")
    val sign = if (negative) "-" else ""
    return sign + CURRENCY_SYMBOL + group(integer) + (if (fraction.isNotEmpty()) ",$fraction" else "")
}

/** €120/ha, €366,67/ha. */
fun formatEuroPerHa(value: Double?, digits: Int? = null): String =
    if (value == null || !value.isFinite()) "-" else "${formatEuro(value, digits)}/ha"

/** Amounts stored in another currency, read in euro. */
fun toEuro(amount: Double, currency: String?): Double {
    if (currency.isNullOrEmpty() || currency.uppercase() == CURRENCY) return amount
    if (currency.uppercase() == "RON") return jsRound((amount / RON_PER_EUR) * 100) / 100
    return amount
}
