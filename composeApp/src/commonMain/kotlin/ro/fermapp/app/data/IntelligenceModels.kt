package ro.fermapp.app.data

import kotlinx.serialization.Serializable
import ro.fermapp.domain.CropCode
import ro.fermapp.domain.NextAction
import ro.fermapp.domain.ParcelPlanData
import ro.fermapp.domain.RotationFactor
import ro.fermapp.domain.RotationVerdict
import ro.fermapp.domain.SoilIndices
import ro.fermapp.domain.SprayAlert
import ro.fermapp.domain.VegetationHealth

/** The response of /parcels/{id}/intelligence, as features/intelligence/api.ts. */

@Serializable
data class WeatherDay(
    val date: String,
    val tMaxC: Double,
    val tMinC: Double,
    val precipitationMm: Double,
    val humidityPct: Double? = null,
    val windMaxKmh: Double? = null,
    val et0Mm: Double? = null,
    val rainProbabilityPct: Double? = null,
)

@Serializable
data class CurrentConditions(
    val temperatureC: Double,
    val humidityPct: Double,
    val precipitationMm: Double,
    val windKmh: Double,
    val et0Mm: Double? = null,
)

@Serializable
data class ParcelWeather(
    val current: CurrentConditions? = null,
    val today: WeatherDay? = null,
    val next7: List<WeatherDay> = emptyList(),
    val next14: List<WeatherDay> = emptyList(),
    val alerts: List<SprayAlert> = emptyList(),
)

@Serializable
data class SatellitePoint(val date: String, val ndvi: Double, val ndre: Double? = null, val healthIndex: Double, val health: VegetationHealth)

@Serializable
data class ParcelPhoto(val id: String, val imageUri: String? = null, val notes: String? = null, val createdAt: String)

@Serializable
data class ParcelObservation(val id: String, val text: String, val createdAt: String, val author: PersonRef)

@Serializable
data class IntelligenceHeader(
    val id: String,
    val name: String,
    val code: String,
    val areaHa: Double,
    val county: String? = null,
    val locality: String? = null,
    val lat: Double,
    val lng: Double,
)

@Serializable
data class IntelligenceCrop(
    val seasonId: String,
    val code: CropCode,
    val name: String,
    val year: Int,
    val sowingDate: String? = null,
    val status: String,
    val vegetationDay: Int,
    val totalDays: Int,
    val percent: Int,
    val estimatedHarvestDate: String? = null,
)

@Serializable
data class RotationYear(val year: Int, val crop: CropCode, val name: String)

/** RotationResult plus the mode and the history the server adds. */
@Serializable
data class IntelligenceRotation(
    val crop: CropCode,
    val year: Int,
    val score: Int,
    val verdict: RotationVerdict,
    val label: String,
    val explanation: String,
    val factors: List<RotationFactor> = emptyList(),
    val mode: String,
    val history: List<RotationYear> = emptyList(),
)

@Serializable
data class IrrigationCoverage(
    val configured: Boolean,
    val irrigatedHa: Double? = null,
    val notIrrigatedHa: Double? = null,
    val coveragePct: Double? = null,
)

@Serializable
data class SavedPlan(val id: String, val year: Int, val createdAt: String, val data: ParcelPlanData)

@Serializable
data class PlanHistoryItem(val id: String, val year: Int, val cropName: String, val trigger: String, val active: Boolean, val createdAt: String)

@Serializable
data class TaskSummary(val todo: Int, val inProgress: Int, val done: Int)

@Serializable
data class IntelligenceTasks(val summary: TaskSummary, val items: List<Task>)

@Serializable
data class IntelligenceSoil(
    val status: String,
    val label: String? = null,
    val ph: Double? = null,
    val clayPct: Double? = null,
    val sandPct: Double? = null,
    val siltPct: Double? = null,
    val organicMatterPct: Double? = null,
    val organicCarbonGKg: Double? = null,
    val errorMessage: String? = null,
    val indices: SoilIndices? = null,
)

@Serializable
data class IntelligenceSatellite(
    val latest: SatellitePoint? = null,
    val sevenDaysAgo: SatellitePoint? = null,
    val thirtyDaysAgo: SatellitePoint? = null,
)

@Serializable
data class ParcelIntelligence(
    val generatedAt: String,
    val header: IntelligenceHeader,
    val crop: IntelligenceCrop? = null,
    val rotation: IntelligenceRotation,
    val irrigation: IrrigationCoverage,
    val activeIrrigation: List<IrrigationRun> = emptyList(),
    val activeWork: List<WorkSession> = emptyList(),
    val nextAction: NextAction,
    val plan: SavedPlan? = null,
    val planHistory: List<PlanHistoryItem> = emptyList(),
    val tasks: IntelligenceTasks,
    val weather: ParcelWeather? = null,
    val soil: IntelligenceSoil,
    val satellite: IntelligenceSatellite = IntelligenceSatellite(),
    val photos: List<ParcelPhoto> = emptyList(),
    val observations: List<ParcelObservation> = emptyList(),
)

@Serializable
data class CropRecommendationView(val crop: CropCode, val name: String, val score: Int, val reasons: List<String>, val missingData: List<String> = emptyList())

@Serializable
data class RecommendationsResponse(val year: Int, val recommendations: List<CropRecommendationView>)

fun intelligenceKey(parcelId: String) = "intelligence/$parcelId"
