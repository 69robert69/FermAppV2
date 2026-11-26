package ro.fermapp.domain

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max

@Serializable
data class SoilInput(
    val ph: Double,
    val clayPct: Double,
    val sandPct: Double,
    /** Organic carbon, in g/kg. */
    val organicCarbonGKg: Double,
    /** Water at field capacity and at wilting point, in % by volume. */
    val fieldCapacityPct: Double? = null,
    val wiltingPointPct: Double? = null,
)

@Serializable
data class SoilClassification(
    val siltPct: Double,
    val organicMatterPct: Double,
    val waterHoldingMmPerM: Int,
    val texture: SoilTexture,
    val textureLabel: String,
    val soilType: String,
    val label: String,
)

/** Van Bemmelen factor: organic matter is about 58% carbon. */
private const val VAN_BEMMELEN = 1.724

private fun classifyTexture(clayPct: Double, sandPct: Double): Pair<SoilTexture, String> = when {
    clayPct >= 40 -> SoilTexture.CLAY to "clay"
    clayPct >= 27 -> SoilTexture.CLAY_LOAM to "clay loam"
    sandPct >= 65 -> SoilTexture.SANDY to "sandy"
    else -> SoilTexture.LOAMY to "loam"
}

private fun classifySoilType(ph: Double, clayPct: Double, sandPct: Double, organicMatterPct: Double): String = when {
    sandPct >= 75 -> "Arenosol"
    clayPct >= 50 -> "Vertisol"
    ph >= 8.3 -> "Kastanozem"
    organicMatterPct >= 2.8 && ph >= 6 && ph < 8.3 -> "Chernozem"
    organicMatterPct >= 2.8 -> "Phaeozem"
    ph < 5.8 -> "Luvisol"
    else -> "Eutric Cambisol"
}

/** Estimates available water when SoilGrids does not provide retention values. */
private fun estimateAvailableWaterPct(clayPct: Double, sandPct: Double, organicMatterPct: Double): Double {
    val base = 18 - abs(clayPct - 25) * 0.12 - max(0.0, sandPct - 50) * 0.18
    return max(5.0, base + organicMatterPct * 0.6)
}

/**
 * Indicative classification based on soil properties.
 * The official soil type can only be established by a pedological survey (SRTS).
 */
fun classifySoil(input: SoilInput): SoilClassification {
    val organicMatterPct = jsRound(((input.organicCarbonGKg * VAN_BEMMELEN) / 10) * 100) / 100
    val (texture, textureLabel) = classifyTexture(input.clayPct, input.sandPct)
    val soilType = classifySoilType(input.ph, input.clayPct, input.sandPct, organicMatterPct)
    val fc = input.fieldCapacityPct
    val wp = input.wiltingPointPct
    val availablePct = if (fc != null && wp != null && fc > wp) {
        fc - wp
    } else {
        estimateAvailableWaterPct(input.clayPct, input.sandPct, organicMatterPct)
    }

    return SoilClassification(
        siltPct = max(0.0, jsRound((100 - input.clayPct - input.sandPct) * 10) / 10),
        organicMatterPct = organicMatterPct,
        waterHoldingMmPerM = jsRoundInt(availablePct * 10),
        texture = texture,
        textureLabel = textureLabel,
        soilType = soilType,
        label = "$soilType ($textureLabel)",
    )
}
