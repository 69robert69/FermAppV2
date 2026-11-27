package com.fermapp.api.domain.soil

import com.fermapp.api.common.util.Js
import com.fermapp.api.domain.crops.Crops.SoilTexture
import kotlin.math.abs
import kotlin.math.max

/** Soil classification (soil.ts) and soil indices (intelligence.ts). */
object SoilRules {

    /**
     * Soil properties from SoilGrids.
     *
     * @param organicCarbonGKg organic carbon, in g/kg
     * @param fieldCapacityPct water at field capacity, in % by volume (optional)
     * @param wiltingPointPct water at wilting point, in % by volume (optional)
     */
    @JvmRecord
    data class SoilInput(
        val ph: Double,
        val clayPct: Double,
        val sandPct: Double,
        val organicCarbonGKg: Double,
        val fieldCapacityPct: Double?,
        val wiltingPointPct: Double?,
    )

    @JvmRecord
    data class SoilClassification(
        val siltPct: Double,
        val organicMatterPct: Double,
        val waterHoldingMmPerM: Double,
        val texture: SoilTexture,
        val textureLabel: String,
        val soilType: String,
        val label: String,
    )

    @JvmRecord
    data class SoilIndices(val soilScore: Int, val fertilityIndex: Int, val waterRetentionIndex: Int)

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
    @JvmStatic
    fun classifySoil(input: SoilInput): SoilClassification {
        val organicMatterPct = Math.round(input.organicCarbonGKg * VAN_BEMMELEN / 10 * 100) / 100.0
        val (texture, textureLabel) = classifyTexture(input.clayPct, input.sandPct)
        val soilType = classifySoilType(input.ph, input.clayPct, input.sandPct, organicMatterPct)
        val fieldCapacity = input.fieldCapacityPct
        val wiltingPoint = input.wiltingPointPct
        val availablePct = if (fieldCapacity != null && wiltingPoint != null && fieldCapacity > wiltingPoint) {
            fieldCapacity - wiltingPoint
        } else {
            estimateAvailableWaterPct(input.clayPct, input.sandPct, organicMatterPct)
        }
        return SoilClassification(
            max(0.0, Js.round1(100 - input.clayPct - input.sandPct)),
            organicMatterPct,
            Js.round(availablePct * 10),
            texture,
            textureLabel,
            soilType,
            "$soilType ($textureLabel)",
        )
    }

    /** Indicative 0-100 indices, computed from soil properties. */
    @JvmStatic
    fun soilIndices(ph: Double, organicMatterPct: Double, clayPct: Double, sandPct: Double, waterHoldingMmPerM: Double): SoilIndices {
        val phDistance = if (ph < 6.2) 6.2 - ph else if (ph > 7.5) ph - 7.5 else 0.0
        val phScore = Js.clamp(100 - phDistance * 40, 0.0, 100.0)
        val organicScore = Js.clamp(organicMatterPct / 4 * 100, 0.0, 100.0)
        val clayDistance = if (clayPct < 15) 15 - clayPct else if (clayPct > 40) clayPct - 40 else 0.0
        val textureScore = Js.clamp(100 - clayDistance * 2.5 - max(0.0, sandPct - 60) * 2, 0.0, 100.0)
        val fertilityIndex = Math.round(organicScore * 0.6 + phScore * 0.4).toInt()
        val waterRetentionIndex = Math.round(Js.clamp(waterHoldingMmPerM / 200 * 100, 0.0, 100.0)).toInt()
        val soilScore = Math.round(fertilityIndex * 0.4 + waterRetentionIndex * 0.3 + textureScore * 0.3).toInt()
        return SoilIndices(soilScore, fertilityIndex, waterRetentionIndex)
    }
}
