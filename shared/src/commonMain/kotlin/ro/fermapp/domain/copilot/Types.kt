package ro.fermapp.domain.copilot

import kotlinx.serialization.Serializable
import ro.fermapp.domain.CropCode
import ro.fermapp.domain.TillageSystem

/*
 * Everything the copilot knows about the farm, gathered from every module
 * before a question is answered. Dates are ISO strings; amounts are in euro.
 */

@Serializable
data class FarmSnapshot(
    /** The moment of the question. */
    val now: String,
    val farmName: String,
    val parcels: List<CopilotParcel>,
    val tasks: List<CopilotTask>,
    val runs: List<CopilotRun>,
    val machines: List<CopilotMachine>,
    val members: List<CopilotMember>,
    /** Forecast at the farm centre: parcels of one farm share the weather. */
    val weather: CopilotWeather?,
    /** Operators do not see costs and profit. */
    val canSeeFinance: Boolean,
)

@Serializable
data class CopilotSeason(
    val year: Int,
    val crop: CropCode,
    val sowingDate: String? = null,
    val harvestDate: String? = null,
    val yieldT: Double? = null,
    val expectedYieldTHa: Double? = null,
    val expectedPricePerT: Double? = null,
    val actualPricePerT: Double? = null,
)

@Serializable
data class CopilotSoil(
    val ph: Double? = null,
    val organicMatterPct: Double? = null,
    val clayPct: Double? = null,
    val sandPct: Double? = null,
    val waterHoldingMmPerM: Double? = null,
    /** Textural class, for example "Clay loam". */
    val texture: String? = null,
    val label: String? = null,
)

@Serializable
data class CopilotEconomics(
    val year: Int,
    val costPerHa: Double,
    val totalCost: Double,
    /** Cost share by category, for example { Fuel: 22 }. */
    val costShares: Map<String, Double>,
    val expectedProfitPerHa: Double?,
    val actualProfitPerHa: Double?,
    val revenuePerHa: Double?,
)

@Serializable
data class Observation(val date: String, val text: String)

@Serializable
data class CopilotParcel(
    val id: String,
    val name: String,
    val code: String? = null,
    val areaHa: Double,
    val tillage: TillageSystem? = null,
    val irrigatedHa: Double? = null,
    val notes: String? = null,
    /** Newest first. */
    val seasons: List<CopilotSeason> = emptyList(),
    val soil: CopilotSoil? = null,
    val economics: CopilotEconomics? = null,
    /** Newest first. */
    val observations: List<Observation> = emptyList(),
    /** The latest analysed satellite image, when monitoring is on. */
    val satellite: CopilotSatellite? = null,
)

@Serializable
enum class Severity { INFO, WARNING, CRITICAL }

@Serializable
enum class TaskPriority3 { LOW, MEDIUM, HIGH }

@Serializable
data class SatelliteTask(val type: String, val title: String, val reason: String, val priority: TaskPriority3)

@Serializable
data class SatelliteFindingSummary(
    val severity: Severity,
    val title: String,
    val message: String,
    val explanation: String,
    val task: SatelliteTask?,
)

@Serializable
data class CopilotSatellite(
    val date: String,
    val ndvi: Double,
    val ndre: Double?,
    val ndmi: Double?,
    /** 0-100, or null without a crop. */
    val healthScore: Int?,
    val healthLabel: String,
    /** NDVI change since about a week and a month earlier. */
    val change7d: Double?,
    val change30d: Double?,
    val findings: List<SatelliteFindingSummary>,
)

@Serializable
enum class CopilotTaskStatus { TODO, IN_PROGRESS, DONE }

@Serializable
enum class CopilotPriority { LOW, MEDIUM, HIGH, URGENT }

@Serializable
enum class TaskSource { AI, USER, PLAN }

@Serializable
data class CopilotTask(
    val id: String,
    val parcelId: String?,
    val title: String,
    val type: String,
    val priority: CopilotPriority,
    val status: CopilotTaskStatus,
    val dueDate: String,
    val assigneeId: String? = null,
    val machineId: String? = null,
    val source: TaskSource = TaskSource.USER,
)

@Serializable
enum class RunKind { WORK, IRRIGATION }

@Serializable
enum class RunStatus { RUNNING, PAUSED, COMPLETED, CANCELLED }

@Serializable
data class CopilotRun(
    val id: String,
    val kind: RunKind,
    val parcelId: String,
    /** Work type (PLOWING, SPRAYING…) or irrigation system. */
    val type: String,
    val status: RunStatus,
    val startDate: String,
    val finishDate: String? = null,
    val machineId: String? = null,
    val operatorId: String? = null,
    val areaHa: Double,
    val doseMm: Double? = null,
)

@Serializable
data class CopilotMachine(
    val id: String,
    val name: String,
    /** TRACTOR, COMBINE, SPRAYER, SEEDER, DISC, CULTIVATOR, SUBSOILER, TRAILER. */
    val type: String,
    val powerHp: Double? = null,
)

@Serializable
enum class MemberRole { ADMIN, FARM_MANAGER, OPERATOR, AGRONOMIST }

@Serializable
data class CopilotMember(val id: String, val name: String, val role: MemberRole)

@Serializable
data class CopilotDay(
    val date: String,
    val tMinC: Double,
    val tMaxC: Double,
    val precipitationMm: Double,
    val rainProbabilityPct: Double? = null,
    val windMaxKmh: Double? = null,
    val et0Mm: Double? = null,
)

@Serializable
data class CopilotHour(
    val time: String,
    val temperatureC: Double,
    val humidityPct: Double,
    val precipitationMm: Double,
    val precipitationProbabilityPct: Double? = null,
    val windKmh: Double,
    val weatherCode: Int? = null,
)

@Serializable
data class CopilotWeather(
    /** Today first. */
    val days: List<CopilotDay>,
    /** Upcoming hours in local time, for spraying windows. */
    val hours: List<CopilotHour>,
    /** Rain of the last 30 days, when known. */
    val past30RainMm: Double? = null,
)

// ------------------------------------------------------------------ answers

@Serializable
enum class CopilotTopic {
    NEXT_CROP, CROP_SUITABILITY, FERTILIZE, HARVEST, IRRIGATE, SPRAY, YIELD, PENDING, PROFITABILITY,
    MACHINERY, REDUCE_COSTS, TILLAGE, PRIORITIZE, ROI_RANKING, WEEK_SCHEDULE, HECTARES_LEFT, IDLE_MACHINES,
    FARM_PROFIT, OVERDUE, RISKS, SATELLITE, RESCHEDULE, CANCEL, REPRIORITIZE, GROUP, CREATE_TASK, WHY, GENERAL,
}

@Serializable
enum class TaskCategory { TILLAGE, PLANTING, FERTILIZATION, CROP_PROTECTION, IRRIGATION, MONITORING, HARVEST }

@Serializable
data class DateWindow(val start: String, val end: String)

/** A task the copilot recommends; it becomes a real To-Do item once approved. */
@Serializable
data class TaskProposal(
    /** Stable within one answer, used by the screen to track approval. */
    val key: String,
    val title: String,
    /** Task type code, see COPILOT_TASK_TYPES. */
    val type: String,
    val category: TaskCategory,
    val parcelId: String?,
    /** YYYY-MM-DD. */
    val dueDate: String,
    /** Suggested time window, YYYY-MM-DD. */
    val window: DateWindow,
    val priority: CopilotPriority,
    val reason: String,
    val assigneeId: String? = null,
    val machineId: String? = null,
    /** Planned after this one is created, for example soil sampling after harvest. */
    val followUps: List<TaskProposal> = emptyList(),
)

@Serializable
enum class ChangeKind { RESCHEDULE, CANCEL, PRIORITY }

/** A change to an existing task: move, cancel or reprioritize. */
@Serializable
data class TaskChange(
    val key: String,
    val kind: ChangeKind,
    val taskId: String,
    val title: String,
    val parcelId: String?,
    val fromDate: String,
    val toDate: String? = null,
    val fromPriority: CopilotPriority,
    val toPriority: CopilotPriority? = null,
    val reason: String,
)

@Serializable
enum class Confidence { HIGH, MEDIUM, LOW }

@Serializable
data class CopilotAnswer(
    val topic: CopilotTopic,
    /** The parcel the answer is about, remembered for follow-up questions. */
    val parcelId: String?,
    val crop: CropCode?,
    val direct: String,
    val reasoning: List<String> = emptyList(),
    val risks: List<String> = emptyList(),
    val alternatives: List<String> = emptyList(),
    val economics: List<String> = emptyList(),
    val nextSteps: List<String> = emptyList(),
    val proposals: List<TaskProposal> = emptyList(),
    val changes: List<TaskChange> = emptyList(),
    /** Information that would make the answer more precise. */
    val dataGaps: List<String> = emptyList(),
    val confidence: Confidence,
    /**
     * The user explicitly asked for the tasks ("add them to To-Do", "yes"), so the
     * request is itself the approval: the app creates them without a second tap.
     */
    val execute: Boolean = false,
)

/** What the copilot remembers between questions of one conversation. */
@Serializable
data class CopilotMemory(
    val parcelId: String? = null,
    val topic: CopilotTopic? = null,
    val crop: CropCode? = null,
    /** The previous answer, so "why?" can expand it. */
    val last: CopilotAnswer? = null,
)

val EMPTY_MEMORY = CopilotMemory()

@Serializable
enum class InsightKind { OVERDUE, UPCOMING, WEATHER, MACHINERY, FERTILIZATION, HARVEST, IRRIGATION, PROFIT, DATA, SATELLITE }

/** Something the copilot noticed without being asked. */
@Serializable
data class CopilotInsight(
    val key: String,
    val severity: Severity,
    val kind: InsightKind,
    val parcelId: String?,
    val message: String,
    /** A question that opens the matching analysis in the chat. */
    val ask: String?,
    val proposal: TaskProposal?,
)
