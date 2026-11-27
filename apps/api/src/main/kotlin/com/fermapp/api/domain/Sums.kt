package com.fermapp.api.domain

/**
 * Sum of doubles with `DoubleStream.sum()`, which uses compensated summation. Kotlin's `sumOf`
 * adds term by term, so its totals can differ in the last bit; the domain rules use this to
 * keep every figure identical to the Java rules they were ported from.
 */
internal inline fun <T> Collection<T>.sumOfDoubles(crossinline selector: (T) -> Double): Double =
    stream().mapToDouble { selector(it) }.sum()
