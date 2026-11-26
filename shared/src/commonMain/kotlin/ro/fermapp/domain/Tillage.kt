package ro.fermapp.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.max
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/*
 * Soil tillage technologies. Each one is a plain profile in TILLAGE_SYSTEMS:
 * a new technology (for example vertical tillage or ridge till) is added there,
 * and every calculation, warning and chart below picks it up.
 */

@Serializable
enum class TillageSystem { CONVENTIONAL, MINIMUM_TILL, STRIP_TILL, NO_TILL }

val TILLAGE_CODES: List<TillageSystem> = TillageSystem.entries

/** Machine types known to the fleet (same values as the server enum). */
@Serializable
enum class TillageMachineType { TRACTOR, COMBINE, SPRAYER, SEEDER, DISC, CULTIVATOR, SUBSOILER, TRAILER }

data class TillageOperation(
    val key: String,
    val label: String,
    /** The work log type used when the operation is started. */
    val workType: WorkOperation,
    /** The task type used when the operation is planned. */
    val taskType: String,
    /** Days after the first operation, used to space the generated tasks. */
    val offsetDays: Int,
)

data class TillageProfile(
    val code: TillageSystem,
    val name: String,
    /** Short map badge. */
    val badge: String,
    /** Categorical colour, validated for colour-blind separation on the app's dark surfaces. */
    val color: String,
    val description: String,
    /** Default economics, editable by the user. */
    val costPerHa: Double,
    val fuelLPerHa: Double,
    /** Soil health score, 0-100. */
    val soilHealth: Int,
    /** Water retention, 1-5 stars. */
    val waterRetention: Int,
    /** Extra water held compared with conventional tillage, in percent. */
    val waterGainPct: Int,
    /** Machine passes over the field in a season, up to sowing. */
    val passes: Int,
    val operations: List<TillageOperation>,
    /** Work types that contradict the technology, with the reason shown to the user. */
    val discouragedWork: Map<WorkOperation, String>,
    /** Task types that contradict the technology. */
    val discouragedTasks: Map<String, String>,
)

private fun op(key: String, label: String, workType: WorkOperation, taskType: String, offsetDays: Int) =
    TillageOperation(key, label, workType, taskType, offsetDays)

val TILLAGE_SYSTEMS: Map<TillageSystem, TillageProfile> = mapOf(
    TillageSystem.CONVENTIONAL to TillageProfile(
        TillageSystem.CONVENTIONAL, "Conventional Tillage", "CONV", "#DE4467",
        "Full inversion with the plough, followed by secondary tillage.",
        520.0, 60.0, 60, 2, 0, 4,
        listOf(
            op("plowing", "Ploughing", WorkOperation.PLOWING, "SOIL_PREP", 0),
            op("discing", "Discing", WorkOperation.DISCING, "SOIL_PREP", 10),
            op("seedbed", "Seedbed preparation", WorkOperation.DISCING, "SOIL_PREP", 18),
            op("sowing", "Sowing", WorkOperation.SOWING, "SOWING", 21),
        ),
        emptyMap(), emptyMap(),
    ),
    TillageSystem.MINIMUM_TILL to TillageProfile(
        TillageSystem.MINIMUM_TILL, "Minimum Till", "MIN", "#B48A10",
        "Loosening without inversion; residues stay near the surface.",
        420.0, 35.0, 75, 4, 15, 3,
        listOf(
            op("subsoiling", "Subsoiling", WorkOperation.SUBSOILING, "SOIL_PREP", 0),
            op("chiselling", "Chiselling", WorkOperation.SUBSOILING, "SOIL_PREP", 7),
            op("shallow-discing", "Shallow discing", WorkOperation.DISCING, "SOIL_PREP", 14),
            op("sowing", "Sowing", WorkOperation.SOWING, "SOWING", 18),
        ),
        mapOf(WorkOperation.PLOWING to "Ploughing inverts the soil and cancels the benefits of Minimum Till."),
        emptyMap(),
    ),
    TillageSystem.STRIP_TILL to TillageProfile(
        TillageSystem.STRIP_TILL, "Strip Till", "STRIP", "#3A8FD0",
        "Only the seed row strips are tilled and fertilised.",
        350.0, 25.0, 82, 4, 18, 2,
        listOf(
            op("strip-prep", "Strip preparation", WorkOperation.SUBSOILING, "SOIL_PREP", 0),
            op("band-fertilizing", "Band fertilisation", WorkOperation.FERTILIZING, "FERTILIZATION", 0),
            op("strip-sowing", "Strip sowing", WorkOperation.SOWING, "SOWING", 10),
        ),
        mapOf(
            WorkOperation.PLOWING to "Ploughing destroys the untilled strips between rows.",
            WorkOperation.DISCING to "Full-width discing works the strips that Strip Till keeps covered.",
        ),
        emptyMap(),
    ),
    TillageSystem.NO_TILL to TillageProfile(
        TillageSystem.NO_TILL, "No Till", "NO-TILL", "#58A23A",
        "Direct sowing into the residue of the previous crop.",
        250.0, 12.0, 90, 5, 20, 1,
        listOf(op("direct-sowing", "Direct sowing", WorkOperation.SOWING, "SOWING", 0)),
        mapOf(
            WorkOperation.PLOWING to "No Till does not plough. Ploughing breaks the soil structure built up over years.",
            WorkOperation.DISCING to "No Till does not disc. Discing buries the residue that protects the soil.",
            WorkOperation.SUBSOILING to "No Till does not loosen the soil before sowing.",
        ),
        mapOf("SOIL_PREP" to "No Till skips seedbed preparation; sow directly into the residue."),
    ),
)

val TillageSystem.profile: TillageProfile get() = TILLAGE_SYSTEMS.getValue(this)

val TILLAGE_LIST: List<TillageProfile> = TILLAGE_CODES.map { it.profile }

/** Savings are measured against this technology. */
val TILLAGE_BASELINE = TillageSystem.CONVENTIONAL

fun parseTillageSystem(value: String?): TillageSystem? = TillageSystem.entries.firstOrNull { it.name == value }

fun tillageProfile(system: TillageSystem?): TillageProfile? = system?.profile

// ------------------------------------------------------------------ settings

@Serializable
data class TillageSetting(val costPerHa: Double? = null, val fuelLPerHa: Double? = null)

/** The user's own cost and fuel figures per technology. Missing values use the defaults. */
typealias TillageSettings = Map<TillageSystem, TillageSetting>

fun tillageCostPerHa(system: TillageSystem, settings: TillageSettings = emptyMap()) =
    settings[system]?.costPerHa ?: system.profile.costPerHa

fun tillageFuelPerHa(system: TillageSystem, settings: TillageSettings = emptyMap()) =
    settings[system]?.fuelLPerHa ?: system.profile.fuelLPerHa

// ------------------------------------------------------------------ one parcel

@Serializable
data class ParcelTillageEstimate(
    val system: TillageSystem,
    val areaHa: Double,
    val costPerHa: Double,
    val totalCost: Double,
    /** Savings compared with conventional tillage, for the whole parcel. Negative when it costs more. */
    val costSavings: Double,
    val fuelPerHa: Double,
    /** Diesel for one season on the parcel. */
    val seasonFuelL: Double,
    /** Diesel for the year, for every season grown on the parcel. */
    val annualFuelL: Double,
    val annualFuelSavingsL: Double,
    val passes: Int,
    val passesSaved: Int,
    val soilHealth: Int,
    val waterRetention: Int,
    val waterGainPct: Int,
)

private fun r(value: Double, digits: Int = 0) = jsRound(value, digits)

fun estimateParcelTillage(
    system: TillageSystem,
    areaHa: Double,
    settings: TillageSettings = emptyMap(),
    seasonsPerYear: Int = 1,
): ParcelTillageEstimate {
    val profile = system.profile
    val baseline = TILLAGE_BASELINE.profile
    val seasons = max(1, seasonsPerYear)
    val costPerHa = tillageCostPerHa(system, settings)
    val fuelPerHa = tillageFuelPerHa(system, settings)
    val seasonFuelL = fuelPerHa * areaHa
    val baselineFuel = tillageFuelPerHa(TILLAGE_BASELINE, settings) * areaHa * seasons
    return ParcelTillageEstimate(
        system = system,
        areaHa = areaHa,
        costPerHa = costPerHa,
        totalCost = r(costPerHa * areaHa * seasons),
        costSavings = r((tillageCostPerHa(TILLAGE_BASELINE, settings) - costPerHa) * areaHa * seasons),
        fuelPerHa = fuelPerHa,
        seasonFuelL = r(seasonFuelL),
        annualFuelL = r(seasonFuelL * seasons),
        annualFuelSavingsL = r(baselineFuel - seasonFuelL * seasons),
        passes = profile.passes,
        passesSaved = baseline.passes - profile.passes,
        soilHealth = profile.soilHealth,
        waterRetention = profile.waterRetention,
        waterGainPct = profile.waterGainPct,
    )
}

enum class SoilHealthLevel { POOR, FAIR, GOOD, EXCELLENT }

data class SoilHealthReading(val label: String, val level: SoilHealthLevel)

/** Plain-language reading of a soil health score. */
fun soilHealthLabel(score: Double): SoilHealthReading = when {
    score >= 85 -> SoilHealthReading("Excellent: active soil life, stable structure", SoilHealthLevel.EXCELLENT)
    score >= 70 -> SoilHealthReading("Good: structure and organic matter are protected", SoilHealthLevel.GOOD)
    score >= 55 -> SoilHealthReading("Fair: regular tillage wears the structure down", SoilHealthLevel.FAIR)
    else -> SoilHealthReading("Poor: high risk of erosion and compaction", SoilHealthLevel.POOR)
}

fun waterRetentionMessage(system: TillageSystem): String {
    val gain = system.profile.waterGainPct
    return if (gain > 0) {
        "The parcel can hold about $gain% more water than under conventional tillage."
    } else {
        "Reference level. Bare, inverted soil loses water to evaporation faster."
    }
}

// ------------------------------------------------------------------ compatibility

data class CompatibilityCheck(val compatible: Boolean, val message: String?)

private val OK = CompatibilityCheck(true, null)

fun checkWorkCompatibility(system: TillageSystem?, workType: String?): CompatibilityCheck {
    if (system == null || workType.isNullOrEmpty()) return OK
    val work = WorkOperation.entries.firstOrNull { it.name == workType } ?: return OK
    val reason = system.profile.discouragedWork[work] ?: return OK
    return CompatibilityCheck(false, "Not recommended for ${system.profile.name}. $reason")
}

fun checkTaskCompatibility(system: TillageSystem?, taskType: String?): CompatibilityCheck {
    if (system == null || taskType.isNullOrEmpty()) return OK
    val reason = system.profile.discouragedTasks[taskType] ?: return OK
    return CompatibilityCheck(false, "Not recommended for ${system.profile.name}. $reason")
}

/**
 * Technologies a machine type suits when the user has not set them. A
 * conventional seed drill cannot cut through residue, so it is not for No Till.
 */
val DEFAULT_MACHINE_TILLAGE: Map<TillageMachineType, List<TillageSystem>> = mapOf(
    TillageMachineType.TRACTOR to TILLAGE_CODES,
    TillageMachineType.COMBINE to TILLAGE_CODES,
    TillageMachineType.SPRAYER to TILLAGE_CODES,
    TillageMachineType.TRAILER to TILLAGE_CODES,
    TillageMachineType.SEEDER to listOf(TillageSystem.CONVENTIONAL, TillageSystem.MINIMUM_TILL),
    TillageMachineType.DISC to listOf(TillageSystem.CONVENTIONAL, TillageSystem.MINIMUM_TILL),
    TillageMachineType.CULTIVATOR to listOf(TillageSystem.CONVENTIONAL, TillageSystem.MINIMUM_TILL),
    TillageMachineType.SUBSOILER to listOf(TillageSystem.CONVENTIONAL, TillageSystem.MINIMUM_TILL, TillageSystem.STRIP_TILL),
)

/** What the tillage checks need to know about a machine. */
data class TillageMachine(val type: String, val tillageSystems: List<String>? = null, val name: String? = null)

fun machineTillageSystems(machine: TillageMachine): List<TillageSystem> {
    val own = (machine.tillageSystems ?: emptyList()).mapNotNull(::parseTillageSystem)
    if (own.isNotEmpty()) return own
    val type = TillageMachineType.entries.firstOrNull { it.name == machine.type }
    return type?.let { DEFAULT_MACHINE_TILLAGE[it] } ?: TILLAGE_CODES
}

fun checkMachineCompatibility(machine: TillageMachine?, system: TillageSystem?): CompatibilityCheck {
    if (machine == null || system == null) return OK
    if (system in machineTillageSystems(machine)) return OK
    val subject = if (!machine.name.isNullOrEmpty()) "${machine.name} is" else "The selected machine is"
    return CompatibilityCheck(false, "$subject not recommended for ${system.profile.name}.")
}

// ------------------------------------------------------------------ operations and advice

data class PlannedOperation(val operation: TillageOperation, val dueDate: Instant)

/** The recommended operations, dated from the first one. */
fun planTillageOperations(system: TillageSystem, start: Instant): List<PlannedOperation> =
    system.profile.operations.map { PlannedOperation(it, start + it.offsetDays.days) }

private val ROW_CROPS = setOf(CropCode.CORN, CropCode.SUNFLOWER, CropCode.SOY, CropCode.BEET)

enum class AdviceTone { INFO, WARNING, SUCCESS }

data class TillageAdvice(val tone: AdviceTone, val message: String)

/** Advice for the technology, adjusted to the crop and to how dry the season is. */
fun tillageRecommendations(system: TillageSystem, crop: CropCode? = null, drought: Boolean = false): List<TillageAdvice> {
    val rowCrop = crop != null && crop in ROW_CROPS
    val advice = mutableListOf<TillageAdvice>()
    when (system) {
        TillageSystem.CONVENTIONAL -> {
            advice.add(TillageAdvice(AdviceTone.WARNING, "High compaction risk from repeated passes. Consider Minimum Till."))
            if (drought) advice.add(TillageAdvice(AdviceTone.WARNING, "In a dry season, ploughing dries the topsoil. Conservation tillage keeps more moisture."))
        }
        TillageSystem.MINIMUM_TILL -> {
            advice.add(TillageAdvice(AdviceTone.SUCCESS, "A good fit for corn and sunflower."))
            if (rowCrop) advice.add(TillageAdvice(AdviceTone.INFO, "For row crops, Strip Till can cut costs further with banded fertiliser."))
        }
        TillageSystem.STRIP_TILL -> {
            advice.add(TillageAdvice(AdviceTone.SUCCESS, "Efficient for row crops and placed fertilisation."))
            if (crop != null && !rowCrop) advice.add(TillageAdvice(AdviceTone.WARNING, "Strip Till pays off mostly in wide-row crops; check the row spacing of this crop."))
        }
        TillageSystem.NO_TILL -> {
            advice.add(TillageAdvice(AdviceTone.SUCCESS, "Excellent moisture conservation in dry conditions."))
            advice.add(TillageAdvice(AdviceTone.INFO, "Use a No Till drill and manage residue and weeds closely in the first years."))
        }
    }
    return advice
}

// ------------------------------------------------------------------ farm analysis

@Serializable
data class TillageHistoryEntry(val year: Int, val system: TillageSystem)

data class ParcelTillageInput(
    val areaHa: Double,
    val system: TillageSystem?,
    val history: List<TillageHistoryEntry>? = null,
)

@Serializable
data class TillageBreakdown(
    val system: TillageSystem,
    val parcels: Int,
    val areaHa: Double,
    val totalCost: Double,
    val fuelL: Double,
)

@Serializable
data class TillageAnalysis(
    val totalAreaHa: Double,
    val assignedAreaHa: Double,
    val unassignedAreaHa: Double,
    val bySystem: List<TillageBreakdown>,
    val totalCost: Double,
    val totalFuelL: Double,
    /** Compared with the same area under conventional tillage. */
    val costSavings: Double,
    val fuelSavingsL: Double,
    val passes: Double,
    val passesSaved: Double,
    /** Fewer passes, as a share of the conventional passes. */
    val passReductionPct: Double,
    /** Area-weighted averages over the parcels that have a technology. */
    val avgSoilHealth: Double?,
    val avgWaterRetention: Double?,
)

fun analyzeTillage(parcels: List<ParcelTillageInput>, settings: TillageSettings = emptyMap()): TillageAnalysis {
    val bySystem = TILLAGE_CODES.associateWith { TillageBreakdown(it, 0, 0.0, 0.0, 0.0) }.toMutableMap()
    var totalAreaHa = 0.0
    var costSavings = 0.0
    var fuelSavingsL = 0.0
    var passes = 0.0
    var baselinePasses = 0.0
    var soil = 0.0
    var water = 0.0
    for (parcel in parcels) {
        totalAreaHa += parcel.areaHa
        val system = parcel.system ?: continue
        val estimate = estimateParcelTillage(system, parcel.areaHa, settings)
        val row = bySystem.getValue(system)
        bySystem[system] = row.copy(
            parcels = row.parcels + 1,
            areaHa = row.areaHa + parcel.areaHa,
            totalCost = row.totalCost + estimate.totalCost,
            fuelL = row.fuelL + estimate.seasonFuelL,
        )
        costSavings += estimate.costSavings
        fuelSavingsL += estimate.annualFuelSavingsL
        passes += estimate.passes * parcel.areaHa
        baselinePasses += TILLAGE_BASELINE.profile.passes * parcel.areaHa
        soil += estimate.soilHealth * parcel.areaHa
        water += estimate.waterRetention * parcel.areaHa
    }
    val rows = TILLAGE_CODES.map { system ->
        val row = bySystem.getValue(system)
        row.copy(areaHa = r(row.areaHa, 2), totalCost = r(row.totalCost), fuelL = r(row.fuelL))
    }
    val assignedAreaHa = rows.sumOf { it.areaHa }
    return TillageAnalysis(
        totalAreaHa = r(totalAreaHa, 2),
        assignedAreaHa = r(assignedAreaHa, 2),
        unassignedAreaHa = r(max(0.0, totalAreaHa - assignedAreaHa), 2),
        bySystem = rows,
        totalCost = r(rows.sumOf { it.totalCost }),
        totalFuelL = r(rows.sumOf { it.fuelL }),
        costSavings = r(costSavings),
        fuelSavingsL = r(fuelSavingsL),
        // Passes are counted per hectare-season, so large parcels weigh more.
        passes = r(passes, 1),
        passesSaved = r(baselinePasses - passes, 1),
        passReductionPct = if (baselinePasses > 0) r(((baselinePasses - passes) / baselinePasses) * 100) else 0.0,
        avgSoilHealth = if (assignedAreaHa > 0) r(soil / assignedAreaHa) else null,
        avgWaterRetention = if (assignedAreaHa > 0) r(water / assignedAreaHa, 1) else null,
    )
}

/** The technology a parcel used in a year: the latest history entry up to that year. */
fun tillageInYear(parcel: ParcelTillageInput, year: Int): TillageSystem? =
    (parcel.history ?: emptyList()).filter { it.year <= year }.maxByOrNull { it.year }?.system

@Serializable
data class TillageYearPoint(
    val year: Int,
    val areaBySystem: Map<TillageSystem, Double>,
    val avgSoilHealth: Double?,
    val avgWaterRetention: Double?,
    val fuelL: Double,
    val totalCost: Double,
)

/** Year-by-year evolution from the parcel histories, for the executive charts. */
fun tillageTimeline(parcels: List<ParcelTillageInput>, years: List<Int>, settings: TillageSettings = emptyMap()): List<TillageYearPoint> =
    years.map { year ->
        val inYear = parcels.map { ParcelTillageInput(it.areaHa, tillageInYear(it, year)) }
        val analysis = analyzeTillage(inYear, settings)
        TillageYearPoint(
            year = year,
            areaBySystem = analysis.bySystem.associate { it.system to it.areaHa },
            avgSoilHealth = analysis.avgSoilHealth,
            avgWaterRetention = analysis.avgWaterRetention,
            fuelL = analysis.totalFuelL,
            totalCost = analysis.totalCost,
        )
    }

/** Sets the technology of one year in a history, keeping one entry per year, oldest first. */
fun withTillageYear(history: List<TillageHistoryEntry>?, entry: TillageHistoryEntry): List<TillageHistoryEntry> =
    ((history ?: emptyList()).filter { it.year != entry.year } + entry).sortedBy { it.year }

/** Reads a stored history, dropping entries for unknown technologies. */
fun parseTillageHistory(value: JsonElement?): List<TillageHistoryEntry> {
    if (value !is JsonArray) return emptyList()
    return value.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val yearValue = (obj["year"] as? JsonPrimitive)?.takeIf { !it.isString }
        val year = yearValue?.intOrNull ?: yearValue?.doubleOrNull?.toInt() ?: return@mapNotNull null
        val system = parseTillageSystem((obj["system"] as? JsonPrimitive)?.takeIf { it.isString }?.content) ?: return@mapNotNull null
        TillageHistoryEntry(year, system)
    }.sortedBy { it.year }
}
