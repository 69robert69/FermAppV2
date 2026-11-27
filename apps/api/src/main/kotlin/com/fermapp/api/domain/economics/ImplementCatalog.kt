package com.fermapp.api.domain.economics

import java.util.Optional

/**
 * The part of catalog.ts the server uses: indicative values for common trailed implements
 * and the default diesel price. The seed and tractor catalogs live only in the mobile app.
 */
object ImplementCatalog {

    /** Diesel price in euro per litre. */
    const val DEFAULT_FUEL_PRICE_EUR = 1.45

    /**
     * A trailed implement.
     *
     * @param hpPerMeter power required for each metre of working width
     * @param fuelLoad share of the tractor's consumption range used for this operation
     */
    @JvmRecord
    data class ImplementEntry(
        val id: String,
        val name: String,
        val operation: String,
        val widthsM: List<Double>,
        val speedKmh: Double,
        val efficiencyPct: Double,
        val hpPerMeter: Double,
        val fuelLoad: Double,
    )

    @JvmField
    val IMPLEMENTS: List<ImplementEntry> = listOf(
        ImplementEntry("plug", "Reversible plough", "PLOWING", listOf(1.6, 2.1, 2.8, 3.5), 7.0, 80.0, 90.0, 0.8),
        ImplementEntry("disc", "Disc harrow", "DISCING", listOf(3.0, 4.0, 6.0, 8.0), 11.0, 80.0, 30.0, 0.6),
        ImplementEntry("scarificator", "Subsoiler", "SUBSOILING", listOf(2.5, 3.0, 4.0, 5.0), 8.0, 80.0, 70.0, 0.8),
        ImplementEntry("semanatoare", "Seed drill", "SOWING", listOf(3.0, 4.5, 6.0, 8.0, 12.0), 8.0, 70.0, 22.0, 0.35),
        ImplementEntry("fertilizator", "Fertilizer spreader", "FERTILIZING", listOf(12.0, 18.0, 24.0), 12.0, 70.0, 3.0, 0.25),
        ImplementEntry("erbicidator", "Field sprayer", "SPRAYING", listOf(18.0, 24.0, 28.0), 10.0, 70.0, 4.0, 0.25),
    )

    @JvmStatic
    fun forOperation(operation: String?): Optional<ImplementEntry> =
        Optional.ofNullable(IMPLEMENTS.firstOrNull { it.operation == operation })
}
