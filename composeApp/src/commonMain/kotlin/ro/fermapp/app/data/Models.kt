package ro.fermapp.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import ro.fermapp.domain.CropCode
import ro.fermapp.domain.PolygonGeometry
import ro.fermapp.domain.TillageHistoryEntry
import ro.fermapp.domain.TillageSystem

/** The API's response shapes, as in apps/mobile/src/api/types.ts. */

@Serializable
enum class Role { ADMIN, FARM_MANAGER, OPERATOR, AGRONOMIST }

@Serializable
data class SeasonRef(val crop: CropCode, val year: Int)

@Serializable
data class SoilRef(val status: String, val label: String? = null)

@Serializable
data class SatelliteRef(val date: String, val ndvi: Double, val healthIndex: Double, val healthClass: String)

@Serializable
data class Parcel(
    val id: String,
    val name: String,
    val code: String,
    val geometry: PolygonGeometry,
    val areaHa: Double,
    val perimeterM: Double,
    val centerLat: Double,
    val centerLng: Double,
    val locality: String? = null,
    val county: String? = null,
    val notes: String? = null,
    val irrigatedAreaHa: Double? = null,
    val tillageSystem: TillageSystem? = null,
    val tillageHistory: List<TillageHistoryEntry>? = null,
    val archivedAt: String? = null,
    val seasons: List<SeasonRef>? = null,
    val soilProfile: SoilRef? = null,
    val satellite: List<SatelliteRef>? = null,
)

@Serializable
data class CropSeason(
    val id: String,
    val year: Int,
    val crop: CropCode,
    val sowingDate: String? = null,
    val harvestDate: String? = null,
    val yieldT: Double? = null,
    val harvestMoisture: Double? = null,
    val notes: String? = null,
    val seedName: String? = null,
)

@Serializable
data class LiveProgress(
    val percent: Double,
    val elapsedSeconds: Double,
    val remainingSeconds: Double,
    val eta: String? = null,
    val finished: Boolean,
)

@Serializable
enum class RunStatus { RUNNING, PAUSED, COMPLETED, CANCELLED }

@Serializable
data class ParcelRef(val id: String, val name: String, val code: String? = null)

@Serializable
data class NamedRef(val id: String, val name: String)

@Serializable
data class PersonRef(val id: String, val fullName: String)

/** Field work and irrigation share the timer fields. */
interface TimedRun {
    val id: String
    val status: RunStatus
    val startedAt: String
    val pausedAt: String?
    val pausedMs: Double
    val durationHours: Double
    val progressPct: Double
    val progress: LiveProgress
    val parcel: ParcelRef
}

@Serializable
data class WorkSession(
    override val id: String,
    override val status: RunStatus,
    override val startedAt: String,
    override val pausedAt: String? = null,
    override val pausedMs: Double = 0.0,
    override val durationHours: Double,
    override val progressPct: Double = 0.0,
    override val progress: LiveProgress,
    override val parcel: ParcelRef,
    val type: String,
    val areaHa: Double,
    val capacityHaH: Double,
    val areaDoneHa: Double = 0.0,
    val fuelLiters: Double? = null,
    val machine: NamedRef? = null,
    val operator: PersonRef,
    /** Present on /map/overview, for drawing the machine on the parcel. */
    val workingWidthM: Double? = null,
    val speedKmh: Double? = null,
    val lastLocation: MachinePosition? = null,
) : TimedRun

@Serializable
data class MachinePosition(val lat: Double, val lng: Double, val speedKmh: Double? = null, val recordedAt: String)

@Serializable
data class MapCrop(val code: CropCode, val name: String, val planned: Boolean, val year: Int)

/** One parcel on /map/overview: crop, growth, open tasks and what runs on it now. */
@Serializable
data class MapParcelInfo(
    val parcelId: String,
    val crop: MapCrop? = null,
    val growthPct: Double? = null,
    val openTasks: Int = 0,
    val ndvi: Double? = null,
    val work: List<WorkSession> = emptyList(),
    val irrigation: List<IrrigationRun> = emptyList(),
)

@Serializable
enum class IrrigationSystem { REEL, PIVOT, LINEAR }

@Serializable
data class IrrigationRun(
    override val id: String,
    override val status: RunStatus,
    override val startedAt: String,
    override val pausedAt: String? = null,
    override val pausedMs: Double = 0.0,
    override val durationHours: Double,
    override val progressPct: Double = 0.0,
    override val progress: LiveProgress,
    override val parcel: ParcelRef,
    val systemType: IrrigationSystem,
    val routeLengthM: Double,
    val speedMPerH: Double,
    val doseMm: Double? = null,
    val label: String? = null,
) : TimedRun

@Serializable
enum class TaskStatus { TODO, IN_PROGRESS, DONE }

@Serializable
enum class TaskPriority { LOW, MEDIUM, HIGH, URGENT }

@Serializable
data class Task(
    val id: String,
    val type: String,
    val title: String,
    val description: String? = null,
    val priority: TaskPriority,
    val status: TaskStatus,
    val dueDate: String,
    val parcel: NamedRef? = null,
    val assignee: PersonRef? = null,
    val planStepKey: String? = null,
    val source: String? = null,
    val category: String? = null,
    val machine: NamedRef? = null,
    val windowStart: String? = null,
    val windowEnd: String? = null,
    val aiReason: String? = null,
)

@Serializable
data class Machine(
    val id: String,
    val name: String,
    val type: String,
    val brand: String? = null,
    val model: String? = null,
    val powerHp: Double? = null,
    val avgConsumptionLh: Double? = null,
    val fuelConsumptionMin: Double? = null,
    val fuelConsumptionMax: Double? = null,
    val tankCapacity: Double? = null,
    val weight: Double? = null,
    val tillageSystems: List<TillageSystem>? = null,
    val operator: PersonRef? = null,
)

@Serializable
data class WeatherAlert(
    val id: String,
    val type: String,
    val severity: String,
    val title: String,
    val message: String,
    val createdAt: String,
    val parcel: NamedRef,
)

@Serializable
data class ParcelCounts(val count: Int, val totalAreaHa: Double, val cultivatedAreaHa: Double, val freeAreaHa: Double)

@Serializable
data class CropArea(val crop: CropCode, val name: String, val areaHa: Double)

@Serializable
data class TaskCounts(val open: Int, val overdue: Int)

@Serializable
data class Dashboard(
    val year: Int,
    val parcels: ParcelCounts,
    val crops: List<CropArea>,
    val activeWork: List<WorkSession>,
    val activeIrrigation: List<IrrigationRun>,
    val tasks: TaskCounts,
    val alerts: List<WeatherAlert>,
)

@Serializable
data class TeamMember(val userId: String, val fullName: String, val email: String, val role: Role)

@Serializable
data class SessionUser(val id: String, val email: String, val fullName: String)

@Serializable
data class FarmRef(val id: String, val name: String, val role: Role? = null)

/** The response of /auth/login and /auth/register, kept on the phone. */
@Serializable
data class Session(
    val user: SessionUser,
    val farm: FarmRef,
    val role: Role,
    val farms: List<FarmRef>? = null,
    val accessToken: String,
    val refreshToken: String,
)

@Serializable
data class Tokens(val accessToken: String, val refreshToken: String)

@Serializable
data class ApiErrorDetail(val field: String, val message: String)

@Serializable
data class ApiErrorBody(val code: String, val message: String, val details: List<ApiErrorDetail>? = null)

@Serializable
data class ApiEnvelope(val data: JsonElement? = null, val error: ApiErrorBody? = null)

fun canManage(role: Role?) = role == Role.ADMIN || role == Role.FARM_MANAGER
fun canPlan(role: Role?) = canManage(role) || role == Role.AGRONOMIST
fun canOperate(role: Role?) = canManage(role) || role == Role.OPERATOR
