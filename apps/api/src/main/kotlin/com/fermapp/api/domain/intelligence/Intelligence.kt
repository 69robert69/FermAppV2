package com.fermapp.api.domain.intelligence

import com.fermapp.api.common.util.Js
import com.fermapp.api.common.util.Times
import com.fermapp.api.domain.crops.Crops
import com.fermapp.api.domain.model.CostCategory
import com.fermapp.api.domain.model.CropCode
import java.time.Instant
import java.util.EnumMap
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Parcel intelligence rules (intelligence.ts) and cost summaries (costs.ts). */
object Intelligence {

    /** Indicative duration from sowing to harvest, in days. */
    @JvmField
    val CROP_SEASON_DAYS: Map<CropCode, Int> = EnumMap(
        mapOf(
            CropCode.WHEAT to 270, CropCode.BARLEY to 250, CropCode.CORN to 170,
            CropCode.SUNFLOWER to 125, CropCode.RAPESEED to 300, CropCode.SOY to 130,
            CropCode.ALFALFA to 180, CropCode.OAT to 110, CropCode.POTATO to 120,
            CropCode.BEET to 190, CropCode.VEGETABLES to 100,
        ),
    )

    const val SPRAY_WIND_LIMIT_KMH = 15.0
    const val SPRAY_RAIN_LIMIT_PCT = 60.0
    private val NITROGEN_CROPS = setOf(CropCode.WHEAT, CropCode.BARLEY, CropCode.CORN, CropCode.RAPESEED, CropCode.OAT)
    private const val LOW_SOIL_MOISTURE_PCT = 30.0
    private const val WATER_DEFICIT_MM = 20.0

    enum class CropProgressStatus { NOT_SOWN, GROWING, READY, HARVESTED }

    @JvmRecord
    data class CropProgress(
        val status: CropProgressStatus,
        val vegetationDay: Int,
        val totalDays: Int,
        val percent: Int,
        val estimatedHarvestDate: String?,
    )

    @JvmRecord
    data class SprayAlert(val code: String, val message: String)

    enum class ActionPriority { LOW, MEDIUM, HIGH }

    @JvmRecord
    data class NextAction(
        val code: String,
        val action: String,
        val reason: String,
        val priority: ActionPriority,
        val riskLevel: ActionPriority,
        val confidencePct: Int,
        val dueInDays: Int?,
    )

    /** Weekly weather summary used by the next-action rules. */
    @JvmRecord
    data class WeatherSummary(
        val meanHumidityPct: Double,
        val meanTempC: Double,
        val minTempC: Double,
        val rainyDays: Int,
        val precipitationMm: Double,
        val et0Mm: Double,
    )

    @JvmRecord
    data class RecentWork(val type: String?, val date: Instant)

    @JvmRecord
    data class NextActionInput(
        val crop: CropCode?,
        val progress: CropProgress?,
        val weather: WeatherSummary?,
        val soilMoisturePct: Double?,
        val healthClass: String?,
        val recentWork: List<RecentWork>?,
        val now: Instant?,
    )

    @JvmRecord
    data class CostItem(val category: CostCategory, val amount: Double)

    @JvmRecord
    data class CostSummaryInput(
        val areaHa: Double,
        val costs: List<CostItem>,
        val expectedYieldTHa: Double?,
        val expectedPricePerT: Double?,
        val actualYieldT: Double?,
        val actualPricePerT: Double?,
    )

    @JvmRecord
    data class CostSummary(
        val totalCost: Double,
        val costPerHa: Double,
        val byCategory: Map<CostCategory, Double>,
        val estimatedRevenue: Double?,
        val estimatedProfit: Double?,
        val actualRevenue: Double?,
        val actualProfit: Double?,
    )

    @JvmRecord
    data class ParcelEconomics(
        val seedsCost: Double,
        val fertilizerCost: Double,
        val chemicalCost: Double,
        val fuelCost: Double,
        val laborCost: Double,
        val irrigationCost: Double,
        val otherCost: Double,
        val totalCost: Double,
        val costPerHa: Double,
        val expectedIncome: Double?,
        val expectedProfit: Double?,
        val profitMarginPct: Double?,
        val roiPct: Double?,
        val actualIncome: Double?,
        val actualProfit: Double?,
    )

    @JvmStatic
    fun cropProgress(crop: CropCode, sowingDate: Instant?, harvestDate: Instant?, expectedHarvestDate: Instant?, now: Instant): CropProgress {
        val defaultDays = CROP_SEASON_DAYS.getValue(crop)
        if (sowingDate == null || sowingDate.isAfter(now)) {
            return CropProgress(CropProgressStatus.NOT_SOWN, 0, defaultDays, 0, Times.iso(expectedHarvestDate))
        }
        val estimated = harvestDate ?: expectedHarvestDate ?: sowingDate.plusMillis(defaultDays * Times.DAY_MS)
        val sownMs = sowingDate.toEpochMilli()
        val totalDays = max(1L, Math.round((estimated.toEpochMilli() - sownMs).toDouble() / Times.DAY_MS)).toInt()
        val reference = if (harvestDate != null && harvestDate.isBefore(now)) harvestDate else now
        val vegetationDay = min(totalDays.toDouble(), floor((reference.toEpochMilli() - sownMs).toDouble() / Times.DAY_MS) + 1).toInt()
        val percent = Math.round(vegetationDay.toDouble() / totalDays * 100).toInt()
        val status = when {
            harvestDate != null && !harvestDate.isAfter(now) -> CropProgressStatus.HARVESTED
            percent >= 95 -> CropProgressStatus.READY
            else -> CropProgressStatus.GROWING
        }
        return CropProgress(status, vegetationDay, totalDays, percent, Times.iso(estimated))
    }

    /** Spraying conditions for the next few hours: each entry is [wind km/h, rain probability %]. */
    @JvmStatic
    fun sprayingAlerts(windAndRainProbability: List<DoubleArray>): List<SprayAlert> {
        val alerts = mutableListOf<SprayAlert>()
        val wind = windAndRainProbability.firstOrNull()?.get(0) ?: 0.0
        if (wind > SPRAY_WIND_LIMIT_KMH) {
            alerts += SprayAlert("WIND", "Wind ${Math.round(wind)} km/h. Do not spray.")
        }
        val rain = windAndRainProbability.fold(0.0) { top, hour -> max(top, hour[1]) }
        if (rain > SPRAY_RAIN_LIMIT_PCT) {
            alerts += SprayAlert("RAIN", "Rain probability ${Math.round(rain)}%. Postpone spraying.")
        }
        return alerts
    }

    /** Picks the next action using agronomic rules, from most to least urgent. */
    @JvmStatic
    fun nextRecommendedAction(input: NextActionInput): NextAction {
        val crop = input.crop
        val progress = input.progress
        val weather = input.weather
        val soilMoisture = input.soilMoisturePct
        val healthClass = input.healthClass
        val recentWork = input.recentWork.orEmpty()
        val now = input.now ?: Times.now()
        val confidence = 50 +
            (if (weather != null) 15 else 0) +
            (if (progress != null && progress.status != CropProgressStatus.NOT_SOWN) 15 else 0) +
            (if (soilMoisture != null) 10 else 0) +
            (if (!healthClass.isNullOrEmpty()) 5 else 0)

        fun workedWithin(type: String, days: Int) =
            recentWork.any { it.type == type && now.toEpochMilli() - it.date.toEpochMilli() <= days * Times.DAY_MS }

        fun action(code: String, action: String, reason: String, priority: ActionPriority, risk: ActionPriority, dueInDays: Int?) =
            NextAction(code, action, reason, priority, risk, confidence, dueInDays)

        if (crop == null) {
            return action(
                "PLAN_CROP", "Plan the crop for this parcel.", "The parcel has no crop recorded for the current year.",
                ActionPriority.MEDIUM, ActionPriority.LOW, null,
            )
        }
        val name = Crops.name(crop).lowercase()
        if (progress == null || progress.status == CropProgressStatus.NOT_SOWN) {
            return action(
                "SET_SOWING", "Fill in the sowing date.", "Without this date the stage of the $name crop cannot be estimated.",
                ActionPriority.MEDIUM, ActionPriority.LOW, null,
            )
        }
        if (progress.status == CropProgressStatus.HARVESTED) {
            return action(
                "POST_HARVEST", "Prepare the land for the next crop.", "The $name crop has been harvested.",
                ActionPriority.LOW, ActionPriority.LOW, 14,
            )
        }
        if (progress.status == CropProgressStatus.READY) {
            return action(
                "HARVEST", "Prepare for harvest.",
                "The $name crop has reached ${progress.percent}% of its estimated cycle. Check grain moisture.",
                ActionPriority.HIGH, ActionPriority.MEDIUM, 7,
            )
        }
        if (weather != null && weather.minTempC <= 0 && progress.percent < 30) {
            return action(
                "FROST", "Check the plants after the expected frost.",
                "${Math.round(weather.minTempC)} °C is expected and the crop is at a sensitive stage.",
                ActionPriority.HIGH, ActionPriority.HIGH, 2,
            )
        }
        if (healthClass == "PROBLEM") {
            return action(
                "SCOUT", "Inspect the parcel in the field.", "The latest satellite image indicates a possible vegetation problem.",
                ActionPriority.HIGH, ActionPriority.HIGH, 2,
            )
        }
        val waterDeficit = if (weather != null) weather.et0Mm - weather.precipitationMm else 0.0
        val lowMoisture = soilMoisture != null && soilMoisture < LOW_SOIL_MOISTURE_PCT
        if ((lowMoisture || waterDeficit > WATER_DEFICIT_MM) && progress.percent in 15..85) {
            val reason = if (lowMoisture) {
                "Soil moisture is ${Math.round(soilMoisture!!)}%."
            } else {
                "Evapotranspiration exceeds rainfall by ${Math.round(waterDeficit)} mm over the next 7 days."
            }
            return action(
                "IRRIGATE", "Plan an irrigation within the next 3 days.", reason,
                ActionPriority.HIGH, ActionPriority.MEDIUM, 3,
            )
        }
        val foliarRisk = weather != null && weather.meanHumidityPct >= 75 && weather.meanTempC >= 12 &&
            weather.meanTempC <= 28 && weather.rainyDays >= 2
        if (foliarRisk && progress.percent in 30..80 && !workedWithin("SPRAYING", 10)) {
            return action(
                "FUNGICIDE", "Check for foliar diseases and prepare a fungicide treatment within 4 days.",
                "Mean humidity of ${Math.round(weather!!.meanHumidityPct)}% and ${weather.rainyDays} " +
                    "rainy days raise the risk of foliar diseases.",
                ActionPriority.HIGH, ActionPriority.HIGH, 4,
            )
        }
        if (crop in NITROGEN_CROPS && progress.percent in 15..45 && !workedWithin("FERTILIZING", 30)) {
            return action(
                "NITROGEN", "Plan a nitrogen fertilization.",
                "The $name crop is actively growing and no fertilization has been recorded in the last 30 days.",
                ActionPriority.MEDIUM, ActionPriority.MEDIUM, 7,
            )
        }
        if (progress.percent <= 25 && !workedWithin("SPRAYING", 21)) {
            return action(
                "WEEDS", "Check the level of weed infestation.",
                "In the early stages, weeds compete strongly with the crop for water and nutrients.",
                ActionPriority.MEDIUM, ActionPriority.MEDIUM, 5,
            )
        }
        return action(
            "MONITOR", "Keep monitoring the crop.", "The available data does not indicate an urgent intervention.",
            ActionPriority.LOW, ActionPriority.LOW, 7,
        )
    }

    @JvmStatic
    fun summarizeCosts(input: CostSummaryInput): CostSummary {
        val byCategory = LinkedHashMap<CostCategory, Double>()
        CostCategory.entries.forEach { byCategory[it] = 0.0 }
        var total = 0.0
        for (cost in input.costs) {
            byCategory.merge(cost.category, cost.amount) { a, b -> a + b }
            total += cost.amount
        }
        byCategory.replaceAll { _, value -> Js.round2(value) }
        val totalCost = Js.round2(total)
        val expectedYield = input.expectedYieldTHa
        val expectedPrice = input.expectedPricePerT
        val actualYield = input.actualYieldT
        val actualPrice = input.actualPricePerT
        val estimatedRevenue = if (expectedYield != null && expectedPrice != null) {
            Js.round2(expectedYield * input.areaHa * expectedPrice)
        } else {
            null
        }
        val actualRevenue = if (actualYield != null && actualPrice != null) Js.round2(actualYield * actualPrice) else null
        return CostSummary(
            totalCost,
            if (input.areaHa > 0) Js.round2(totalCost / input.areaHa) else 0.0,
            byCategory,
            estimatedRevenue,
            estimatedRevenue?.let { Js.round2(it - totalCost) },
            actualRevenue,
            actualRevenue?.let { Js.round2(it - totalCost) },
        )
    }

    @JvmStatic
    fun parcelEconomics(input: CostSummaryInput, recordedIncome: Double): ParcelEconomics {
        val summary = summarizeCosts(input)
        val expectedIncome = summary.estimatedRevenue
        val expectedProfit = summary.estimatedProfit
        val actualIncome = if (recordedIncome != 0.0) recordedIncome else summary.actualRevenue
        val c = summary.byCategory
        val totalCost = summary.totalCost
        return ParcelEconomics(
            c.getValue(CostCategory.SEED), c.getValue(CostCategory.FERTILIZER), c.getValue(CostCategory.PESTICIDE),
            c.getValue(CostCategory.FUEL), c.getValue(CostCategory.LABOR), c.getValue(CostCategory.IRRIGATION),
            c.getValue(CostCategory.OTHER),
            totalCost, summary.costPerHa, expectedIncome, expectedProfit,
            if (expectedIncome != null && expectedIncome != 0.0 && expectedProfit != null) {
                Js.round1(expectedProfit / expectedIncome * 100)
            } else {
                null
            },
            if (totalCost > 0 && expectedProfit != null) Js.round1(expectedProfit / totalCost * 100) else null,
            actualIncome,
            actualIncome?.let { Js.round2(it - totalCost) },
        )
    }
}
