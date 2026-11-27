package com.fermapp.api.modules.ai

import com.fermapp.api.common.util.Js
import com.fermapp.api.common.util.Times
import com.fermapp.api.domain.crops.CropRules
import com.fermapp.api.domain.crops.Crops
import com.fermapp.api.domain.economics.EconomicModels.EconomicCategory
import com.fermapp.api.domain.economics.Economics
import com.fermapp.api.domain.economics.Money
import com.fermapp.api.domain.model.CropCode
import com.fermapp.api.domain.model.SoilStatus
import com.fermapp.api.domain.rotation.Rotation
import com.fermapp.api.domain.rotation.Rotation.CropHistoryEntry
import com.fermapp.api.domain.sumOfDoubles
import com.fermapp.api.domain.tillage.Tillage
import com.fermapp.api.integrations.OpenMeteoClient
import com.fermapp.api.integrations.OpenMeteoClient.Forecast
import com.fermapp.api.integrations.OpenMeteoClient.WeatherDay
import com.fermapp.api.modules.economics.EconomicsService
import com.fermapp.api.modules.economics.EconomicsService.FarmEconomicsResult
import com.fermapp.api.modules.work.WorkViews
import com.fermapp.api.persistence.entity.Parcel
import com.fermapp.api.persistence.repository.CropSeasonRepository
import com.fermapp.api.persistence.repository.ParcelRepository
import com.fermapp.api.persistence.repository.SatelliteObservationRepository
import com.fermapp.api.persistence.repository.SoilProfileRepository
import com.fermapp.api.persistence.repository.WorkSessionRepository
import java.util.Locale
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** The parcel data given to the assistant, read only from the user's farm (ai.context.ts). */
@Service
class ParcelContextService(
    private val parcels: ParcelRepository,
    private val seasons: CropSeasonRepository,
    private val soilProfiles: SoilProfileRepository,
    private val satellite: SatelliteObservationRepository,
    private val works: WorkSessionRepository,
    private val openMeteo: OpenMeteoClient,
    private val economics: EconomicsService,
) {

    @JvmRecord
    data class ParcelInfo(val name: String?, val code: String?, val areaHa: Double, val locality: String?, val county: String?)

    @JvmRecord
    data class SeasonInfo(val year: Int, val crop: String, val yieldTHa: Double?)

    @JvmRecord
    data class SoilInfo(
        val label: String?,
        val ph: Double?,
        val organicMatterPct: Double?,
        val clayPct: Double?,
        val waterHoldingMmPerM: Double?,
    )

    @JvmRecord
    data class VegetationInfo(val date: String, val ndvi: Double, val ndre: Double?, val healthIndex: Int, val label: String)

    @JvmRecord
    data class WorkInfo(val date: String?, val work: String, val status: String)

    @JvmRecord
    data class RotationOption(val crop: String, val score: Int, val verdict: String, val explanation: String)

    @JvmRecord
    data class WeekWeather(val precipitationMm: Double, val tMinC: Double, val tMaxC: Double, val maxWindKmh: Double)

    @JvmRecord
    data class TillageYear(val year: Int, val system: String)

    @JvmRecord
    data class TillageInfo(
        val system: String,
        val soilHealthScore: Int,
        val waterRetentionStars: Int,
        val estimatedCostPerHa: Double,
        val estimatedFuelLPerHa: Double,
        val discouragedOperations: List<String>,
        val advice: List<String>,
        val history: List<TillageYear>,
    )

    @JvmRecord
    data class EconomicsInfo(
        val costPerHaEur: Double,
        val totalCostEur: Double,
        val shareByCategoryPct: Map<String, Long>,
        val expectedProfitPerHaEur: Double?,
        val actualProfitPerHaEur: Double?,
        val findings: List<String>,
    )

    @JvmRecord
    data class ParcelContext(
        val parcel: ParcelInfo,
        val year: Int,
        val currentCrop: String?,
        val cropHistory: List<SeasonInfo>,
        val soil: SoilInfo?,
        val vegetation: VegetationInfo?,
        val recentWork: List<WorkInfo>,
        val rotationNextYear: List<RotationOption>,
        val weatherNext7Days: WeekWeather?,
        val currentWindKmh: Double?,
        val tillage: TillageInfo?,
        val economics: EconomicsInfo?,
    )

    fun build(farmId: UUID, parcelId: UUID): ParcelContext? {
        val parcel = parcels.findByIdAndFarmId(parcelId, farmId).orElse(null) ?: return null
        val list = seasons.findTop10ByParcelIdOrderByYearDesc(parcelId)
        val year = Times.currentLocalYear()
        val current = list.firstOrNull { it.year == year }
        val soil = soilProfiles.findByParcelId(parcelId).filter { it.status == SoilStatus.READY }.orElse(null)
        val observation = satellite.findFirstByParcelIdOrderByDateDesc(parcelId).orElse(null)

        val forecast: Forecast? = try {
            openMeteo.getForecast(parcel.centerLat, parcel.centerLng)
        } catch (error: RuntimeException) {
            log.warn("Forecast unavailable for the assistant: {}", error.message)
            null
        }
        val week: List<WeatherDay> = forecast?.days?.let { it.subList(Math.min(1, it.size), Math.min(8, it.size)) } ?: listOf()
        val history = list.map { CropHistoryEntry(it.crop, it.year) }
        val upcoming = if (forecast == null) listOf() else OpenMeteoClient.upcomingHours(forecast, Times.now())

        return ParcelContext(
            ParcelInfo(parcel.name, parcel.code, Js.round2(parcel.areaHa), parcel.locality, parcel.county),
            year,
            current?.let { Crops.name(it.crop) },
            list.map { season ->
                SeasonInfo(season.year, Crops.name(season.crop), season.yieldT?.let { Js.round2(it / parcel.areaHa) })
            },
            soil?.let { SoilInfo(it.label, it.ph, it.organicMatterPct, it.clayPct, it.waterHoldingMmPerM) },
            observation?.let {
                VegetationInfo(it.date.toString(), it.ndvi, it.ndre, it.healthIndex, CropRules.vegetationHealth(it.ndvi, it.ndre).label)
            },
            works.findTop8ByParcelIdOrderByStartedAtDesc(parcelId).map {
                WorkInfo(Times.isoDay(it.startedAt), WorkViews.workLabel(it.type), it.status.name)
            },
            Rotation.rankRotationOptions(year + 1, history, Crops.CROP_CODES).take(5).map {
                RotationOption(Crops.name(it.crop), it.score, it.verdict.name, it.explanation)
            },
            if (week.isEmpty()) {
                null
            } else {
                WeekWeather(
                    Js.round1(week.sumOfDoubles { it.precipitationMm }),
                    week.minOf { it.tMinC },
                    week.maxOf { it.tMaxC },
                    week.maxOf { it.windMaxKmh ?: 0.0 },
                )
            },
            if (forecast == null || upcoming.isEmpty()) null else upcoming[0].windKmh(),
            tillageContext(parcel, current?.crop),
            economicsContext(farmId, parcelId, year, parcel.tillageSystem),
        )
    }

    private fun economicsContext(farmId: UUID, parcelId: UUID, year: Int, tillage: String?): EconomicsInfo? {
        val farm: FarmEconomicsResult = try {
            economics.farmEconomics(farmId, year, parcelId)
        } catch (error: RuntimeException) {
            log.warn("Economics unavailable for the assistant: {}", error.message)
            return null
        }
        val item = farm.parcels.firstOrNull() ?: return null
        val season = item.result.season
        if (season.total <= 0) {
            return null
        }
        val shares = LinkedHashMap<String, Long>()
        for (category in EconomicCategory.entries) {
            val value = season.byCategory.getValue(category)
            if (value > 0) {
                shares[Economics.LABELS.getValue(category)] = Math.round(value / season.total * 100)
            }
        }
        return EconomicsInfo(
            season.perHa, season.total, shares, item.result.expected?.profitPerHa, item.result.actual?.profitPerHa,
            Economics.economicInsights(item.result, Tillage.parse(tillage)).map { it.message },
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(ParcelContextService::class.java)

        private fun tillageContext(parcel: Parcel, crop: CropCode?): TillageInfo? {
            val system = Tillage.parse(parcel.tillageSystem) ?: return null
            val profile = Tillage.profile(system)
            val estimate = Tillage.estimateParcelTillage(system, parcel.areaHa)
            return TillageInfo(
                profile.name, estimate.soilHealth, estimate.waterRetention, estimate.costPerHa, estimate.fuelPerHa,
                profile.discouragedWork.values.toMutableList(),
                Tillage.tillageRecommendations(system, crop).map { it.message },
                Tillage.parseTillageHistory(parcel.tillageHistory).map { TillageYear(it.year, Tillage.profile(it.system).name) },
            )
        }

        /** `toLowerCase()` with the default locale, as before. */
        private fun lower(text: String): String = text.lowercase(Locale.getDefault())

        /** Answer computed from data, used when the AI model is not configured or does not respond. */
        @JvmStatic
        fun summarizeWithoutModel(context: ParcelContext?): String {
            if (context == null) {
                return "Choose a parcel to get an analysis based on its history, soil and forecast."
            }
            val parts = mutableListOf<String>()
            val soil = context.soil
            if (soil?.ph != null && soil.organicMatterPct != null) {
                parts += "Parcel ${context.parcel.name} has pH ${Js.num(soil.ph)} and ${Js.num(soil.organicMatterPct)}% organic matter."
            } else {
                parts += "Parcel ${context.parcel.name} covers ${Js.num(context.parcel.areaHa)} ha, and its soil profile is not available yet."
            }
            context.economics?.let { economics ->
                val findings = economics.findings.take(2).joinToString(" ")
                parts += ("The real production cost is " + Money.formatEuro(economics.costPerHaEur, 2) + "/ha. " + findings).trim { it <= ' ' }
            }
            context.tillage?.let { tillage ->
                val advice = tillage.advice.firstOrNull() ?: ""
                parts += ("The parcel is farmed with ${tillage.system} (soil health ${tillage.soilHealthScore}/100). $advice").trim { it <= ' ' }
            }
            val last = context.cropHistory.firstOrNull { it.year < context.year }
            if (context.currentCrop != null) {
                parts += "The ${context.year} crop is ${lower(context.currentCrop)}."
            } else if (last != null) {
                parts += "The last recorded crop is ${lower(last.crop)}, in ${last.year}."
            }
            val good = context.rotationNextYear.filter { it.verdict == "RECOMMENDED" }.take(2)
            if (good.isNotEmpty()) {
                parts += "For next season, " + good.joinToString(" or ") { lower(it.crop) } + " is recommended."
            }
            val avoid = context.rotationNextYear.lastOrNull()
            if (avoid != null && avoid.verdict == "NOT_RECOMMENDED") {
                parts += avoid.explanation
            }
            context.weatherNext7Days?.let { weather ->
                parts += "Over the next 7 days, ${Js.num(weather.precipitationMm)} mm of rainfall is expected, with temperatures between " +
                    "${Math.round(weather.tMinC)} and ${Math.round(weather.tMaxC)} °C."
            }
            context.vegetation?.let { vegetation ->
                parts += "The latest satellite image (${vegetation.date}) shows NDVI ${Js.num(vegetation.ndvi)}: ${lower(vegetation.label)}."
            }
            return parts.joinToString(" ")
        }
    }
}
