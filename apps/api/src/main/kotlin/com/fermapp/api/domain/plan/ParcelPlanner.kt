package com.fermapp.api.domain.plan

import com.fasterxml.jackson.annotation.JsonInclude
import com.fermapp.api.common.util.Js
import com.fermapp.api.common.util.Times
import com.fermapp.api.common.util.toIsoString
import com.fermapp.api.domain.crops.CropRules
import com.fermapp.api.domain.crops.CropRules.ClimateFit
import com.fermapp.api.domain.crops.CropRules.CropRecommendation
import com.fermapp.api.domain.crops.CropRules.SoilFit
import com.fermapp.api.domain.crops.Crops
import com.fermapp.api.domain.crops.Crops.CropFamily
import com.fermapp.api.domain.crops.Crops.SoilTexture
import com.fermapp.api.domain.model.CropCode
import com.fermapp.api.domain.rotation.Rotation
import com.fermapp.api.domain.rotation.Rotation.CropHistoryEntry
import java.time.Instant
import java.time.ZoneOffset
import java.util.EnumMap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** The technology plan of a parcel for one agricultural year (parcelPlan.ts). */
object ParcelPlanner {

    private const val MIN_DAYS_BEFORE_SOWING = 7
    private const val IRRIGATION_DOSE_MM = 30
    private val MONTHS = listOf(
        "January", "February", "March", "April", "May", "June", "July",
        "August", "September", "October", "November", "December",
    )

    /** The last crop declared in the wizard can be a catalog crop or OTHER. */
    const val OTHER_CROP = "OTHER"

    /**
     * The crop calendar, in days from sowing.
     *
     * @param topDressingDays missing for crops that do not receive top-dressing nitrogen
     * @param criticalFrom first month when water shortage affects yield the most
     * @param rainShare share of annual rainfall available to the crop
     * @param nutrientsPerT N, P2O5 and K2O requirement, in kg per tonne of yield
     */
    @JvmRecord
    @Suppress("ArrayInDataClass")
    data class CropCalendar(
        val sowingMonth: Int,
        val sowingDay: Int,
        val emergenceDays: Int,
        val topDressingDays: Int?,
        val diseaseDays: Int,
        val pestDays: Int,
        val harvestDays: Int,
        val criticalFrom: Int,
        val criticalTo: Int,
        val rainShare: Double,
        val priceEurPerT: Double,
        val costEurHa: Double,
        val nutrientsPerT: DoubleArray,
    )

    private fun calendar(
        sowingMonth: Int, sowingDay: Int, emergenceDays: Int, topDressingDays: Int?, diseaseDays: Int, pestDays: Int,
        harvestDays: Int, criticalFrom: Int, criticalTo: Int, rainShare: Double, priceEurPerT: Int, costEurHa: Int,
        n: Double, p: Double, k: Double,
    ) = CropCalendar(
        sowingMonth, sowingDay, emergenceDays, topDressingDays, diseaseDays, pestDays, harvestDays, criticalFrom, criticalTo,
        rainShare, priceEurPerT.toDouble(), costEurHa.toDouble(), doubleArrayOf(n, p, k),
    )

    /** Indicative values for conditions in Romania. */
    @JvmField
    val CROP_CALENDARS: Map<CropCode, CropCalendar> = EnumMap(
        mapOf(
            CropCode.WHEAT to calendar(10, 5, 12, 165, 205, 225, 275, 4, 5, 0.8, 200, 800, 25.0, 10.0, 6.0),
            CropCode.BARLEY to calendar(9, 28, 12, 165, 195, 215, 270, 4, 5, 0.8, 180, 700, 24.0, 10.0, 7.0),
            CropCode.CORN to calendar(4, 5, 12, 40, 66, 118, 193, 7, 8, 0.6, 190, 1000, 23.0, 10.0, 7.0),
            CropCode.SUNFLOWER to calendar(4, 1, 12, 35, 75, 60, 150, 6, 7, 0.6, 430, 650, 40.0, 18.0, 12.0),
            CropCode.RAPESEED to calendar(8, 28, 8, 190, 225, 205, 305, 4, 5, 0.8, 460, 850, 50.0, 25.0, 12.0),
            CropCode.SOY to calendar(4, 25, 12, null, 70, 85, 145, 7, 8, 0.6, 400, 650, 0.0, 15.0, 12.0),
            CropCode.ALFALFA to calendar(4, 1, 10, null, 40, 50, 75, 6, 8, 0.7, 130, 500, 0.0, 6.0, 20.0),
            CropCode.OAT to calendar(3, 15, 14, 40, 70, 80, 125, 5, 6, 0.6, 170, 550, 25.0, 10.0, 8.0),
            CropCode.POTATO to calendar(4, 5, 20, 35, 60, 50, 130, 6, 7, 0.6, 200, 4000, 4.0, 1.5, 6.0),
            CropCode.BEET to calendar(3, 25, 14, 45, 100, 60, 195, 7, 8, 0.65, 40, 1800, 2.0, 1.0, 2.5),
            CropCode.VEGETABLES to calendar(4, 20, 10, 35, 50, 40, 100, 6, 8, 0.55, 350, 6000, 3.0, 1.5, 4.0),
        ),
    )

    /** One dated step of the plan. [past]: the deadline has passed, so the step no longer becomes a task. */
    @JvmRecord
    data class PlanStep(val key: String, val title: String, val taskType: String, val date: String, val priority: String, val past: Boolean)

    @JvmRecord
    data class PlanSchedule(val sowingDate: String, val harvestDate: String, val seasonYear: Int, val steps: List<PlanStep>)

    @JvmRecord
    data class PlanSoil(val ph: Double, val texture: SoilTexture?, val label: String?)

    @JvmRecord
    data class PlanClimate(val seasonTempC: Double, val annualPrecipitationMm: Double, val gddBase10: Double?)

    /** A crop recommendation with the reason for the score in a single sentence. */
    @JvmRecord
    data class RankedCrop(
        val crop: CropCode,
        val name: String,
        val score: Int,
        val reasons: List<String>,
        val missingData: List<String>,
        val reason: String,
    )

    @JvmRecord
    data class FertilizationStage(val stage: String, val date: String?, val nKgHa: Double, val pKgHa: Double, val kKgHa: Double)

    @JvmRecord
    data class Irrigation(
        val requiredMm: Double,
        val effectiveRainMm: Double?,
        val deficitMm: Double?,
        val passes: Int,
        val doseMm: Int,
        val criticalPeriod: String,
    )

    @JvmRecord
    data class Monitoring(val title: String, val date: String)

    /** The plan report. The three financial fields are left out for operators. */
    @JvmRecord
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    data class ParcelReport(
        val parcelName: String?,
        val areaHa: Double,
        @param:JsonInclude(JsonInclude.Include.ALWAYS) @field:JsonInclude(JsonInclude.Include.ALWAYS)
        val soilType: String?,
        @param:JsonInclude(JsonInclude.Include.ALWAYS) @field:JsonInclude(JsonInclude.Include.ALWAYS)
        val lastCropName: String?,
        val plannedCropName: String?,
        val recommendedCrop: CropCode?,
        val recommendedCropName: String?,
        val recommendedScore: Int,
        val compatibilityScore: Int,
        val expectedYieldTHa: Double,
        val expectedProfitEurHa: Double?,
        val priceEurPerT: Double?,
        val costEurHa: Double?,
        val risks: List<String>?,
        val recommendation: String?,
        val missingData: List<String>?,
    )

    @JvmRecord
    data class ParcelPlanData(
        val year: Int,
        val crop: CropCode,
        val cropName: String,
        val rotationScore: Int,
        val rotationExplanation: String,
        val sowingDate: String,
        val harvestDate: String,
        val steps: List<PlanStep>,
        val irrigation: Irrigation,
        val fertilization: List<FertilizationStage>,
        val monitoring: List<Monitoring>,
        val recommendations: List<RankedCrop>,
        val report: ParcelReport,
    )

    /**
     * Input of [buildParcelPlan].
     *
     * @param history previous crops, including the last declared crop
     * @param lastCropName name of the last crop, when it is not part of the catalog
     * @param year year of an already recorded crop
     */
    @JvmRecord
    data class ParcelPlanInput(
        val crop: CropCode,
        val parcelName: String?,
        val areaHa: Double,
        val history: List<CropHistoryEntry>,
        val lastCropName: String?,
        val soil: PlanSoil?,
        val climate: PlanClimate?,
        val now: Instant?,
        val year: Int?,
        val sowingDate: Instant?,
    )

    /** Next agricultural year. After 1 August, the current year's harvest is considered the last crop. */
    @JvmStatic
    fun nextSeasonYear(now: Instant): Int {
        val utc = now.atZone(ZoneOffset.UTC)
        return if (utc.monthValue - 1 >= 7) utc.year + 1 else utc.year
    }

    private fun startOfDay(date: Instant): Instant {
        val utc = date.atZone(ZoneOffset.UTC)
        return Times.utc(utc.year, utc.monthValue, utc.dayOfMonth, 9)
    }

    private fun addDays(date: Instant, days: Int): Instant = date.plusMillis(days * Times.DAY_MS)

    private fun calendarOf(crop: CropCode): CropCalendar = CROP_CALENDARS.getValue(crop)

    private fun sowingIn(crop: CropCode, year: Int): Instant {
        val calendar = calendarOf(crop)
        return Times.utc(year, calendar.sowingMonth, calendar.sowingDay, 9)
    }

    private fun resolveSowing(crop: CropCode, now: Instant, sowingDate: Instant?, harvestYear: Int?): Instant {
        if (sowingDate != null) {
            return sowingDate
        }
        val harvestDays = calendarOf(crop).harvestDays
        if (harvestYear != null) {
            val sameYear = sowingIn(crop, harvestYear)
            return if (Times.utcYear(addDays(sameYear, harvestDays)) > harvestYear) sowingIn(crop, harvestYear - 1) else sameYear
        }
        val earliest = addDays(startOfDay(now), MIN_DAYS_BEFORE_SOWING)
        val thisYear = sowingIn(crop, Times.utcYear(now))
        return if (!thisYear.isBefore(earliest)) thisYear else sowingIn(crop, Times.utcYear(now) + 1)
    }

    private class StepDefinition(val key: String, val title: String, val taskType: String, val days: Int, val priority: String = "MEDIUM")

    /** The crop's technology calendar, computed from the sowing date. */
    @JvmStatic
    fun planCalendar(crop: CropCode, now: Instant, sowingDate: Instant?, harvestYear: Int?): PlanSchedule {
        val calendar = calendarOf(crop)
        val today = startOfDay(now)
        val sowing = resolveSowing(crop, now, sowingDate, harvestYear)
        val harvest = addDays(sowing, calendar.harvestDays)
        val planned = harvestYear == null && sowingDate == null

        val definitions = buildList {
            add(StepDefinition("SOIL_PREP", "Seedbed preparation", "SOIL_PREP", -26))
            add(StepDefinition("SEED_PURCHASE", "Seed purchase", "SEED_PURCHASE", -11))
            add(StepDefinition("BASE_FERTILIZATION", "Base fertilization", "FERTILIZATION", -6))
            add(StepDefinition("SOWING", "Sowing", "SOWING", 0, "HIGH"))
            add(StepDefinition("PRE_EMERGENT", "Pre-emergence herbicide", "HERBICIDE", 2))
            add(StepDefinition("EMERGENCE_CHECK", "Emergence check", "EMERGENCE_CHECK", calendar.emergenceDays))
            calendar.topDressingDays?.let { add(StepDefinition("TOP_DRESSING", "Top dressing", "FERTILIZATION", it)) }
            add(StepDefinition("PEST_MONITORING", "Pest monitoring", "PEST_CHECK", calendar.pestDays))
            add(StepDefinition("DISEASE_MONITORING", "Disease monitoring", "DISEASE_CHECK", calendar.diseaseDays))
            add(StepDefinition("HARVEST_PREP", "Harvest preparation", "HARVEST_PREP", calendar.harvestDays - 14))
            add(StepDefinition("HARVEST", "Harvest", "HARVEST", calendar.harvestDays, "HIGH"))
        }

        val steps = definitions
            .map { definition ->
                val date = addDays(sowing, definition.days)
                // Overdue preparatory steps in a new plan are done immediately.
                val due = if (planned && date.isBefore(today)) today else date
                PlanStep(definition.key, definition.title, definition.taskType, due.toIsoString(), definition.priority, due.isBefore(today))
            }
            .sortedBy { it.date }
            .toMutableList()
        return PlanSchedule(sowing.toIsoString(), harvest.toIsoString(), Times.utcYear(harvest), steps)
    }

    private fun joinClauses(clauses: List<String>): String = when (clauses.size) {
        0 -> ""
        1 -> clauses[0]
        else -> clauses.dropLast(1).joinToString(", ") + " and " + clauses.last()
    }

    private fun explain(
        recommendation: CropRecommendation,
        year: Int,
        history: List<CropHistoryEntry>,
        soil: PlanSoil?,
        climate: PlanClimate?,
    ): String {
        val profile = Crops.profile(recommendation.crop)
        val rotation = Rotation.evaluateRotation(recommendation.crop, year, history)
        val previous = history.firstOrNull { it.year == year - 1 }
        val good = mutableListOf<String>()
        val weak = mutableListOf<String>()

        if (rotation.score >= 70) {
            if (previous != null && previous.crop != recommendation.crop) {
                val previousProfile = Crops.profile(previous.crop)
                good += "the parcel had ${previousProfile.name.lowercase()} last year"
                if (previousProfile.family == CropFamily.LEGUME) {
                    good += "it uses the nitrogen left in the soil by ${previousProfile.name.lowercase()}"
                }
            } else {
                good += "the rotation is favourable"
            }
            if (profile.family == CropFamily.LEGUME) {
                good += "it enriches the soil with nitrogen"
            }
        } else {
            val text = rotation.explanation.replace(TRAILING_DOT, "")
            weak += if (text.isEmpty()) text else text[0].lowercaseChar() + text.substring(1)
        }

        if (soil != null) {
            val min = profile.phRange[0]
            val max = profile.phRange[1]
            if (soil.ph in min..max) {
                good += "the pH of ${Js.num(soil.ph)} is optimal"
            } else {
                weak += "the pH of ${Js.num(soil.ph)} is outside the ${Js.num(min)}-${Js.num(max)} range"
            }
            if (soil.texture !in profile.textures) {
                weak += "the soil texture is not suitable"
            }
        }
        if (climate != null) {
            val needed = profile.waterMin()
            val rain = Math.round(climate.annualPrecipitationMm)
            if (climate.annualPrecipitationMm >= needed) {
                good += "rainfall of $rain mm covers the minimum requirement"
            } else {
                weak += "rainfall of $rain mm is below the minimum requirement of ${Js.num(needed)} mm"
            }
        }

        return when {
            recommendation.score >= 70 && good.isNotEmpty() -> "${profile.name} is a good fit because ${joinClauses(good)}."
            weak.isNotEmpty() -> "${profile.name} has a lower score because ${joinClauses(weak)}."
            else -> "${profile.name} has an indicative score, because data is missing about ${joinClauses(recommendation.missingData)}."
        }
    }

    private val TRAILING_DOT = Regex("\\.$")

    /** Ranks crops by soil, climate and rotation, with an explanation for each score. */
    @JvmStatic
    fun rankCropsForParcel(year: Int, history: List<CropHistoryEntry>, soil: PlanSoil?, climate: PlanClimate?): List<RankedCrop> {
        val soilFit = soil?.let { SoilFit(it.ph, it.texture) }
        val climateFit = climate?.let { ClimateFit(it.seasonTempC, it.annualPrecipitationMm, it.gddBase10) }
        return CropRules.recommendCrops(year, history, soilFit, climateFit, Crops.CROP_CODES).map { item ->
            RankedCrop(item.crop, item.name, item.score, item.reasons, item.missingData, explain(item, year, history, soil, climate))
        }
    }

    private fun roundTo5(value: Double): Double = Math.round(value / 5) * 5.0

    private fun period(from: Int, to: Int) = MONTHS[from - 1] + "-" + MONTHS[to - 1]

    /** Generates the full crop plan: steps, calendar, water, nutrients, monitoring and report. */
    @JvmStatic
    fun buildParcelPlan(input: ParcelPlanInput): ParcelPlanData {
        val crop = input.crop
        val now = input.now ?: Times.now()
        val history = input.history
        val soil = input.soil
        val climate = input.climate
        val profile = Crops.profile(crop)
        val calendar = calendarOf(crop)
        val schedule = planCalendar(crop, now, input.sowingDate, input.year)
        val year = input.year ?: schedule.seasonYear

        // All crops are compared in the same agricultural year, so the scores stay comparable.
        val latest = history.fold(nextSeasonYear(now)) { top, entry -> max(top, entry.year + 1) }
        val comparisonYear = min(year, latest)
        val recommendations = rankCropsForParcel(comparisonYear, history, soil, climate)
        val chosen = recommendations.first { it.crop == crop }
        val best = recommendations[0]
        val rotation = Rotation.evaluateRotation(crop, comparisonYear, history)

        val requiredMm = (profile.waterMin() + profile.waterMax()) / 2
        val effectiveRainMm = climate?.let { Js.round(it.annualPrecipitationMm * calendar.rainShare) }
        val deficitMm = effectiveRainMm?.let { max(0.0, Js.round(requiredMm - it)) }
        val hasDeficit = deficitMm != null && deficitMm != 0.0
        val passes = if (hasDeficit) ceil(deficitMm!! / IRRIGATION_DOSE_MM).toInt() else 0
        val criticalPeriod = period(calendar.criticalFrom, calendar.criticalTo)

        val waterFactor = if (effectiveRainMm == null) 1.0 else max(0.6, min(1.0, effectiveRainMm / requiredMm))
        val scoreFactor = max(0.6, min(1.05, 0.55 + chosen.score / 100.0 * 0.5))
        val expectedYieldTHa = Math.round(profile.referenceYieldTHa * scoreFactor * waterFactor * 10) / 10.0
        val expectedProfitEurHa = roundTo5(expectedYieldTHa * calendar.priceEurPerT - calendar.costEurHa)

        val previous = history.maxByOrNull { it.year }
        val nitrogenCredit = if (previous != null && Crops.profile(previous.crop).family == CropFamily.LEGUME) 30.0 else 0.0
        val perT = calendar.nutrientsPerT
        val nitrogen = max(0.0, expectedYieldTHa * perT[0] - nitrogenCredit)
        val hasTopDressing = calendar.topDressingDays != null
        val fertilization = mutableListOf(
            FertilizationStage(
                "Base fertilization", dateOf(schedule, "BASE_FERTILIZATION"),
                roundTo5(if (hasTopDressing) nitrogen * 0.3 else nitrogen), roundTo5(expectedYieldTHa * perT[1]),
                roundTo5(expectedYieldTHa * perT[2]),
            ),
        )
        if (hasTopDressing) {
            fertilization += FertilizationStage("Top dressing", dateOf(schedule, "TOP_DRESSING"), roundTo5(nitrogen * 0.7), 0.0, 0.0)
        }

        val risks = mutableListOf<String>()
        if (hasDeficit) {
            risks += "Drought during $criticalPeriod."
            risks += "Rainfall deficit estimated at ${Js.num(deficitMm!!)} mm per season."
        }
        if (rotation.score < 70) {
            risks += rotation.explanation
        }
        if (soil != null && (soil.ph < profile.phRange[0] || soil.ph > profile.phRange[1])) {
            risks += "The pH of ${Js.num(soil.ph)} is outside the optimal range of " +
                "${Js.num(profile.phRange[0])}-${Js.num(profile.phRange[1])}."
        }
        if (risks.isEmpty()) {
            risks += "The available data does not indicate major risks."
        }

        val advice = mutableListOf<String>()
        advice += when {
            hasDeficit -> "Plan at least $passes irrigation ${if (passes == 1) "cycle" else "cycles"} of " +
                "$IRRIGATION_DOSE_MM mm during the critical growth period ($criticalPeriod)."
            effectiveRainMm == null -> "Check the water reserve before the critical period ($criticalPeriod)."
            else -> "Average rainfall covers the water requirement. Monitor soil moisture during $criticalPeriod."
        }
        if (best.crop != crop && best.score - chosen.score >= 10) {
            advice += "${best.name} scores better on this parcel: ${best.score}%."
        }

        val lastCropName = input.lastCropName ?: previous?.let { Crops.name(it.crop) }

        val monitoring = schedule.steps
            .filter { it.key == "EMERGENCE_CHECK" || it.key == "PEST_MONITORING" || it.key == "DISEASE_MONITORING" }
            .map { Monitoring(it.title, it.date) }

        val report = ParcelReport(
            input.parcelName, Js.round2(input.areaHa), soil?.label, lastCropName, profile.name, best.crop, best.name, best.score,
            chosen.score, expectedYieldTHa, expectedProfitEurHa, calendar.priceEurPerT, calendar.costEurHa,
            risks, advice.joinToString(" "), chosen.missingData,
        )

        return ParcelPlanData(
            year, crop, profile.name, rotation.score, rotation.explanation,
            schedule.sowingDate, schedule.harvestDate, schedule.steps,
            Irrigation(requiredMm, effectiveRainMm, deficitMm, passes, IRRIGATION_DOSE_MM, criticalPeriod),
            fertilization, monitoring, recommendations, report,
        )
    }

    private fun dateOf(schedule: PlanSchedule, key: String): String? = schedule.steps.firstOrNull { it.key == key }?.date
}
