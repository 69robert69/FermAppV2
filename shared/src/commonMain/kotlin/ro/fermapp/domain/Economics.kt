package ro.fermapp.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/*
 * Real production cost per hectare, in euro.
 *
 * Inputs (seed, fertiliser, crop protection, other costs and revenue) come
 * from the parcel's economic sheet for the season. Field operations come from
 * the work log: each one is costed for diesel, machine ownership and labour.
 * Operations the crop still needs are projected, so the cost per hectare is
 * realistic from the first day of the season, not only at harvest.
 */

@Serializable
enum class EconomicCategory(val label: String) {
    SEEDS("Seeds"),
    FERTILIZERS("Fertilisers"),
    CHEMICALS("Crop protection"),
    FUEL("Diesel"),
    MACHINERY("Machinery"),
    LABOR("Labour"),
    OTHER("Other costs"),
}

val ECONOMIC_CATEGORIES: List<EconomicCategory> = EconomicCategory.entries

/** Expenses recorded by category land in the matching economic category. */
val RECORDED_TO_ECONOMIC: Map<CostCategory, EconomicCategory> = mapOf(
    CostCategory.SEED to EconomicCategory.SEEDS,
    CostCategory.FERTILIZER to EconomicCategory.FERTILIZERS,
    CostCategory.PESTICIDE to EconomicCategory.CHEMICALS,
    CostCategory.FUEL to EconomicCategory.FUEL,
    CostCategory.LABOR to EconomicCategory.LABOR,
    CostCategory.IRRIGATION to EconomicCategory.OTHER,
    CostCategory.OTHER to EconomicCategory.OTHER,
)

const val DEFAULT_LABOR_RATE_EUR_H = 10.0

/** Working hours in a month, used to turn a monthly salary into an hourly rate. */
const val HOURS_PER_MONTH = 168.0

/** Typical ownership and upkeep per hour when the machine's own figures are missing. */
val DEFAULT_MACHINE_COST_EUR_H: Map<String, Double> = mapOf(
    "TRACTOR" to 35.0, "COMBINE" to 120.0, "SPRAYER" to 45.0, "SEEDER" to 20.0,
    "DISC" to 12.0, "CULTIVATOR" to 12.0, "SUBSOILER" to 14.0, "TRAILER" to 6.0,
)
private const val FALLBACK_MACHINE_COST_EUR_H = 35.0

/** Typical diesel per hectare when neither the log nor the machine says more. */
val DEFAULT_FUEL_L_PER_HA: Map<String, Double> = mapOf(
    "PLOWING" to 20.0, "DISCING" to 9.0, "SUBSOILING" to 15.0, "SOWING" to 6.0,
    "FERTILIZING" to 2.0, "SPRAYING" to 1.5, "HARVESTING" to 16.0,
)

val WORK_OPERATION_LABELS: Map<String, String> = mapOf(
    "PLOWING" to "Ploughing", "DISCING" to "Discing", "SUBSOILING" to "Subsoiling", "SOWING" to "Sowing",
    "FERTILIZING" to "Fertilising", "SPRAYING" to "Spraying", "HARVESTING" to "Harvesting",
)

// ------------------------------------------------------------------ sheet

@Serializable
enum class SeedUnit { SEEDS, KG }

@Serializable
data class SeedLine(
    val id: String,
    val supplier: String = "",
    val hybrid: String = "",
    /** Seed category for cereals, for example Basic, C1 or C2. */
    val category: String? = null,
    /** Corn and sunflower are sold by seeds per bag, cereals by kilograms. */
    val unit: SeedUnit = SeedUnit.SEEDS,
    /** Seeds or kilograms in one bag. */
    val bagSize: Double = 1.0,
    /** Seeds or kilograms sown per hectare. */
    val ratePerHa: Double = 0.0,
    val pricePerBag: Double = 0.0,
    /** Bags actually used; when empty the need is computed from the rate. */
    val bagsUsed: Double? = null,
)

@Serializable
data class FertilizerLine(
    val id: String,
    val product: String = "",
    val ratePerHaKg: Double = 0.0,
    val pricePerT: Double = 0.0,
    val applications: Double? = 1.0,
    /** Nutrient content, in percent. */
    val n: Double? = null,
    val p: Double? = null,
    val k: Double? = null,
)

@Serializable
enum class ChemicalKind(val label: String) {
    HERBICIDE("Herbicide"),
    FUNGICIDE("Fungicide"),
    INSECTICIDE("Insecticide"),
    GROWTH_REGULATOR("Growth regulator"),
}

@Serializable
enum class DoseUnit { L, KG }

@Serializable
data class ChemicalLine(
    val id: String,
    val product: String = "",
    val kind: ChemicalKind = ChemicalKind.HERBICIDE,
    val dosePerHa: Double = 0.0,
    val unit: DoseUnit = DoseUnit.L,
    val pricePerUnit: Double = 0.0,
    val applications: Double? = 1.0,
)

/** PER_HA multiplies by the area; TOTAL is the amount for the whole parcel. */
@Serializable
enum class MiscBasis { PER_HA, TOTAL }

@Serializable
data class MiscLine(val id: String, val label: String = "", val amount: Double = 0.0, val basis: MiscBasis = MiscBasis.TOTAL)

@Serializable
data class RevenueInputs(
    val expectedYieldTHa: Double? = null,
    val actualYieldTHa: Double? = null,
    val pricePerT: Double? = null,
    /** The price actually obtained, when it differs from the planned one. */
    val actualPricePerT: Double? = null,
)

@Serializable
data class EconomicSheet(
    val seeds: List<SeedLine> = emptyList(),
    val fertilizers: List<FertilizerLine> = emptyList(),
    val chemicals: List<ChemicalLine> = emptyList(),
    val misc: List<MiscLine> = emptyList(),
    val revenue: RevenueInputs = RevenueInputs(),
)

fun emptySheet() = EconomicSheet()

// ------------------------------------------------------------------ settings

@Serializable
data class MachineCostProfile(
    val purchasePrice: Double = 0.0,
    /** Value left at the end of the depreciation period, in percent of the purchase price. */
    val residualPct: Double? = 20.0,
    val depreciationYears: Double = 10.0,
    val annualHours: Double = 500.0,
    val serviceCostPerYear: Double? = 0.0,
    val maintenanceCostPerYear: Double? = 0.0,
)

@Serializable
data class LaborProfile(val monthlySalary: Double? = null, val hourlyRate: Double? = null)

@Serializable
data class EconomicSettings(
    val fuelPricePerL: Double = DEFAULT_FUEL_PRICE_EUR,
    val defaultLaborRate: Double = DEFAULT_LABOR_RATE_EUR_H,
    val labor: Map<String, LaborProfile> = emptyMap(),
    val machines: Map<String, MachineCostProfile> = emptyMap(),
)

fun defaultSettings() = EconomicSettings()

// ------------------------------------------------------------------ line costs

private fun round2(value: Double) = jsRound(value * 100) / 100
private fun round1(value: Double) = jsRound(value * 10) / 10
private fun positive(value: Double?, fallback: Double = 0.0) = if (value != null && value.isFinite() && value >= 0) value else fallback

@Serializable
data class SeedCost(
    val line: SeedLine,
    val costPerHa: Double,
    /** For seed sold by weight. */
    val costPerKg: Double?,
    /** For seed sold by count. */
    val costPer1000Seeds: Double?,
    val bagsNeeded: Double,
    val total: Double,
)

fun seedCost(line: SeedLine, areaHa: Double): SeedCost {
    val bag = positive(line.bagSize, 1.0).takeIf { it != 0.0 } ?: 1.0
    val perUnit = positive(line.pricePerBag) / bag
    val costPerHa = perUnit * positive(line.ratePerHa)
    val bagsNeeded = (positive(line.ratePerHa) * areaHa) / bag
    val bagsUsed = line.bagsUsed
    val total = if (bagsUsed != null && bagsUsed >= 0) bagsUsed * positive(line.pricePerBag) else costPerHa * areaHa
    return SeedCost(
        line = line,
        costPerHa = round2(costPerHa),
        costPerKg = if (line.unit == SeedUnit.KG) round2(perUnit) else null,
        costPer1000Seeds = if (line.unit == SeedUnit.SEEDS) round2(perUnit * 1000) else null,
        bagsNeeded = round1(bagsNeeded),
        total = round2(total),
    )
}

@Serializable
data class Nutrients(val n: Int, val p: Int, val k: Int)

@Serializable
data class FertilizerCost(
    val line: FertilizerLine,
    val pricePerKg: Double,
    val costPerHa: Double,
    val total: Double,
    /** Nutrients applied, kg per hectare. */
    val nutrients: Nutrients,
)

fun fertilizerCost(line: FertilizerLine, areaHa: Double): FertilizerCost {
    val applications = max(1.0, positive(line.applications, 1.0))
    val kgPerHa = positive(line.ratePerHaKg) * applications
    val pricePerKg = positive(line.pricePerT) / 1000
    val costPerHa = kgPerHa * pricePerKg
    return FertilizerCost(
        line = line,
        pricePerKg = round2(pricePerKg * 1000) / 1000,
        costPerHa = round2(costPerHa),
        total = round2(costPerHa * areaHa),
        nutrients = Nutrients(
            n = jsRoundInt((kgPerHa * positive(line.n)) / 100),
            p = jsRoundInt((kgPerHa * positive(line.p)) / 100),
            k = jsRoundInt((kgPerHa * positive(line.k)) / 100),
        ),
    )
}

@Serializable
data class ChemicalCost(val line: ChemicalLine, val costPerHa: Double, val total: Double, val quantity: Double)

fun chemicalCost(line: ChemicalLine, areaHa: Double): ChemicalCost {
    val applications = max(1.0, positive(line.applications, 1.0))
    val perHa = positive(line.dosePerHa) * positive(line.pricePerUnit) * applications
    return ChemicalCost(
        line = line,
        costPerHa = round2(perHa),
        total = round2(perHa * areaHa),
        quantity = round1(positive(line.dosePerHa) * applications * areaHa),
    )
}

fun miscCost(line: MiscLine, areaHa: Double) =
    round2(if (line.basis == MiscBasis.PER_HA) positive(line.amount) * areaHa else positive(line.amount))

// ------------------------------------------------------------------ machines and labour

@Serializable
data class MachineHourlyCost(
    val perHour: Double,
    val depreciation: Double,
    val service: Double,
    val maintenance: Double,
    /** True when the typical figure for the machine type was used. */
    val estimated: Boolean,
)

fun machineHourlyCost(profile: MachineCostProfile?, machineType: String? = null): MachineHourlyCost {
    if (profile == null || !(profile.annualHours > 0) || !(profile.depreciationYears > 0)) {
        val perHour = DEFAULT_MACHINE_COST_EUR_H[machineType ?: ""] ?: FALLBACK_MACHINE_COST_EUR_H
        return MachineHourlyCost(perHour, perHour, 0.0, 0.0, true)
    }
    val residual = positive(profile.residualPct, 20.0) / 100
    val depreciation = (positive(profile.purchasePrice) * (1 - residual)) / profile.depreciationYears / profile.annualHours
    val service = positive(profile.serviceCostPerYear) / profile.annualHours
    val maintenance = positive(profile.maintenanceCostPerYear) / profile.annualHours
    return MachineHourlyCost(
        perHour = round2(depreciation + service + maintenance),
        depreciation = round2(depreciation),
        service = round2(service),
        maintenance = round2(maintenance),
        estimated = false,
    )
}

fun laborHourlyRate(profile: LaborProfile?, fallback: Double = DEFAULT_LABOR_RATE_EUR_H): Double {
    val hourly = profile?.hourlyRate
    if (hourly != null && hourly > 0) return hourly
    val monthly = profile?.monthlySalary
    if (monthly != null && monthly > 0) return round2(monthly / HOURS_PER_MONTH)
    return fallback
}

// ------------------------------------------------------------------ operations

@Serializable
enum class OperationStatus { RUNNING, PAUSED, COMPLETED, CANCELLED }

@Serializable
data class OperationMachine(val id: String, val name: String? = null, val type: String, val fuelLPerH: Double? = null)

@Serializable
data class OperationOperator(val id: String, val name: String? = null)

@Serializable
data class OperationInput(
    val id: String,
    val type: String,
    val status: OperationStatus,
    /** Planned area of the operation. */
    val areaHa: Double,
    val progressPct: Double,
    /** Hours actually worked so far. */
    val hours: Double,
    val fuelLiters: Double? = null,
    val machine: OperationMachine? = null,
    val operator: OperationOperator? = null,
    val startedAt: String,
)

@Serializable
data class OperationCost(
    val id: String,
    val type: String,
    val label: String,
    val machineName: String?,
    val operatorName: String?,
    val areaHa: Double,
    val hours: Double,
    val fuelL: Double,
    val fuelCost: Double,
    val machineCost: Double,
    val laborCost: Double,
    val total: Double,
    val costPerHa: Double,
    /** Projected operations are not in the work log yet. */
    val projected: Boolean,
    /** Diesel or machine figures came from typical values. */
    val estimated: Boolean,
    val startedAt: String?,
)

/** Diesel per hectare for an operation, following the tillage technology when one is set. */
fun fuelPerHaFor(type: String, tillage: TillageSystem?): Double {
    val typical = DEFAULT_FUEL_L_PER_HA[type] ?: 5.0
    if (tillage == null) return typical
    val profile = tillage.profile
    val tillageTypes = profile.operations.map { it.workType.name }
    if (type !in tillageTypes) return typical
    // The technology's diesel per hectare is shared between its operations by their typical weight.
    fun weight(name: String) = DEFAULT_FUEL_L_PER_HA[name] ?: 5.0
    val total = tillageTypes.sumOf { weight(it) }
    return round1((profile.fuelLPerHa * weight(type)) / total)
}

private data class OperationBase(
    val id: String,
    val type: String,
    val areaHa: Double,
    val hours: Double,
    val fuelL: Double,
    val machineType: String?,
    val machineId: String?,
    val operatorId: String?,
)

private fun priceOperation(
    base: OperationBase,
    settings: EconomicSettings,
    machineName: String?,
    operatorName: String?,
    projected: Boolean,
    estimated: Boolean,
    startedAt: String?,
): OperationCost {
    val machine = machineHourlyCost(base.machineId?.let { settings.machines[it] }, base.machineType ?: "TRACTOR")
    val labor = laborHourlyRate(base.operatorId?.let { settings.labor[it] }, settings.defaultLaborRate)
    val fuelCost = base.fuelL * settings.fuelPricePerL
    val machineCost = machine.perHour * base.hours
    val laborCost = labor * base.hours
    val total = fuelCost + machineCost + laborCost
    return OperationCost(
        id = base.id,
        type = base.type,
        label = WORK_OPERATION_LABELS[base.type] ?: base.type,
        machineName = machineName,
        operatorName = operatorName,
        areaHa = round2(base.areaHa),
        hours = round1(base.hours),
        fuelL = jsRound(base.fuelL),
        fuelCost = round2(fuelCost),
        machineCost = round2(machineCost),
        laborCost = round2(laborCost),
        total = round2(total),
        costPerHa = if (base.areaHa > 0) round2(total / base.areaHa) else 0.0,
        projected = projected,
        estimated = estimated || machine.estimated,
        startedAt = startedAt,
    )
}

/** Prices one logged operation: diesel, machine hours and operator hours. */
fun costOperation(op: OperationInput, settings: EconomicSettings, tillage: TillageSystem? = null): OperationCost {
    val areaHa = (op.areaHa * op.progressPct.clamp(0.0, 100.0)) / 100
    val hours = max(0.0, op.hours)
    val loggedFuel = op.fuelLiters?.takeIf { it > 0 }
    val machineFuel = op.machine?.fuelLPerH?.takeIf { it != 0.0 }?.let { it * hours }
    val fuelL = loggedFuel ?: machineFuel ?: (fuelPerHaFor(op.type, tillage) * areaHa)
    return priceOperation(
        OperationBase(op.id, op.type, areaHa, hours, fuelL, op.machine?.type, op.machine?.id, op.operator?.id),
        settings,
        machineName = op.machine?.name,
        operatorName = op.operator?.name,
        projected = false,
        estimated = loggedFuel == null && machineFuel == null,
        startedAt = op.startedAt,
    )
}

/** Field capacity of an operation from the implement catalog, in hectares per hour. */
private fun typicalCapacityHaH(type: String): Double {
    if (type == "HARVESTING") return (6 * 6 * 0.7) / 10
    val implement = IMPLEMENT_CATALOG.firstOrNull { it.operation.name == type } ?: return 2.0
    val width = implement.widthsM[floor((implement.widthsM.size - 1) / 2.0).toInt()]
    return (width * implement.speedKmh * (implement.efficiencyPct / 100)) / 10
}

private fun applications(value: Double?) = max(1.0, value ?: 1.0)

/** The operations a season needs: the technology's tillage and sowing, fertilising, spraying and harvest. */
fun seasonOperations(sheet: EconomicSheet, tillage: TillageSystem?, crop: CropCode?): List<String> {
    val types = tillage?.profile?.operations?.map { it.workType.name }?.toMutableList() ?: mutableListOf("DISCING", "SOWING")
    val fertilizerPasses = sheet.fertilizers.fold(0.0) { acc, line -> max(acc, applications(line.applications)) }
    val fertilizing = fertilizerPasses - types.count { it == "FERTILIZING" }
    var i = 0
    while (i < fertilizing) {
        types.add("FERTILIZING")
        i++
    }
    val spraying = sheet.chemicals.sumOf { applications(it.applications) }
    i = 0
    while (i < spraying) {
        types.add("SPRAYING")
        i++
    }
    if (crop != null) types.add("HARVESTING")
    return types
}

/** Estimates the operations still to come, with the typical machine, diesel and labour. */
fun projectOperations(
    needed: List<String>,
    logged: List<OperationInput>,
    areaHa: Double,
    settings: EconomicSettings,
    tillage: TillageSystem? = null,
): List<OperationCost> {
    val done = mutableMapOf<String, Double>()
    for (op in logged) {
        if (op.status == OperationStatus.CANCELLED) continue
        done[op.type] = (done[op.type] ?: 0.0) + min(1.0, op.progressPct / 100)
    }
    val projected = mutableListOf<OperationCost>()
    needed.forEachIndexed { index, type ->
        val covered = done[type] ?: 0.0
        val share = (1 - covered).clamp(0.0, 1.0)
        done[type] = max(0.0, covered - 1)
        if (share <= 0.01) return@forEachIndexed
        val area = areaHa * share
        val hours = area / typicalCapacityHaH(type)
        projected.add(
            priceOperation(
                OperationBase("projected-$index-$type", type, area, hours, fuelPerHaFor(type, tillage) * area, if (type == "HARVESTING") "COMBINE" else "TRACTOR", null, null),
                settings,
                machineName = null,
                operatorName = null,
                projected = true,
                estimated = true,
                startedAt = null,
            ),
        )
    }
    return projected
}

// ------------------------------------------------------------------ parcel

@Serializable
data class CostTotals(val byCategory: Map<EconomicCategory, Double>, val total: Double, val perHa: Double)

@Serializable
data class ProfitResult(
    val yieldTHa: Double,
    val pricePerT: Double,
    val revenue: Double,
    val revenuePerHa: Double,
    val profit: Double,
    val profitPerHa: Double,
    val marginPct: Double?,
)

data class ParcelEconomicsInput(
    val areaHa: Double,
    val sheet: EconomicSheet,
    val operations: List<OperationInput>,
    val settings: EconomicSettings,
    val tillage: TillageSystem? = null,
    val crop: CropCode? = null,
    /** Expenses recorded by category, already in euro. */
    val recorded: List<CostItem> = emptyList(),
)

@Serializable
data class MiscCost(val line: MiscLine, val total: Double)

@Serializable
data class ParcelEconomicsResult(
    val areaHa: Double,
    val seeds: List<SeedCost>,
    val fertilizers: List<FertilizerCost>,
    val chemicals: List<ChemicalCost>,
    val misc: List<MiscCost>,
    val operations: List<OperationCost>,
    val projected: List<OperationCost>,
    val recordedTotal: Double,
    /** Inputs, finished work and recorded expenses: what has been spent so far. */
    val toDate: CostTotals,
    /** Plus the operations still to come: the realistic cost of the season. */
    val season: CostTotals,
    val expected: ProfitResult?,
    val actual: ProfitResult?,
)

private fun emptyCategories(): MutableMap<EconomicCategory, Double> = ECONOMIC_CATEGORIES.associateWith { 0.0 }.toMutableMap()

private fun totals(byCategory: Map<EconomicCategory, Double>, areaHa: Double): CostTotals {
    val rounded = ECONOMIC_CATEGORIES.associateWith { round2(byCategory.getValue(it)) }
    val total = round2(ECONOMIC_CATEGORIES.sumOf { byCategory.getValue(it) })
    return CostTotals(rounded, total, if (areaHa > 0) round2(total / areaHa) else 0.0)
}

private fun profit(areaHa: Double, yieldTHa: Double?, pricePerT: Double?, cost: Double): ProfitResult? {
    if (yieldTHa == null || pricePerT == null || !(yieldTHa > 0) || !(pricePerT > 0)) return null
    val revenue = yieldTHa * pricePerT * areaHa
    val result = revenue - cost
    return ProfitResult(
        yieldTHa = yieldTHa,
        pricePerT = pricePerT,
        revenue = round2(revenue),
        revenuePerHa = round2(yieldTHa * pricePerT),
        profit = round2(result),
        profitPerHa = if (areaHa > 0) round2(result / areaHa) else 0.0,
        marginPct = if (revenue > 0) jsRound((result / revenue) * 1000) / 10 else null,
    )
}

private fun MutableMap<EconomicCategory, Double>.add(category: EconomicCategory, amount: Double) {
    this[category] = getValue(category) + amount
}

fun computeParcelEconomics(input: ParcelEconomicsInput): ParcelEconomicsResult {
    val areaHa = input.areaHa
    val sheet = input.sheet
    val settings = input.settings
    val seeds = sheet.seeds.map { seedCost(it, areaHa) }
    val fertilizers = sheet.fertilizers.map { fertilizerCost(it, areaHa) }
    val chemicals = sheet.chemicals.map { chemicalCost(it, areaHa) }
    val misc = sheet.misc.map { MiscCost(it, miscCost(it, areaHa)) }
    val logged = input.operations.filter { it.status != OperationStatus.CANCELLED || it.progressPct > 0 }
    val operations = logged.map { costOperation(it, settings, input.tillage) }
    val projected = projectOperations(seasonOperations(sheet, input.tillage, input.crop), logged, areaHa, settings, input.tillage)

    val spent = emptyCategories()
    spent.add(EconomicCategory.SEEDS, seeds.sumOf { it.total })
    spent.add(EconomicCategory.FERTILIZERS, fertilizers.sumOf { it.total })
    spent.add(EconomicCategory.CHEMICALS, chemicals.sumOf { it.total })
    spent.add(EconomicCategory.OTHER, misc.sumOf { it.total })
    for (op in operations) {
        spent.add(EconomicCategory.FUEL, op.fuelCost)
        spent.add(EconomicCategory.MACHINERY, op.machineCost)
        spent.add(EconomicCategory.LABOR, op.laborCost)
    }
    var recordedTotal = 0.0
    for (entry in input.recorded) {
        spent.add(RECORDED_TO_ECONOMIC.getValue(entry.category), entry.amount)
        recordedTotal += entry.amount
    }
    val season = spent.toMutableMap()
    for (op in projected) {
        season.add(EconomicCategory.FUEL, op.fuelCost)
        season.add(EconomicCategory.MACHINERY, op.machineCost)
        season.add(EconomicCategory.LABOR, op.laborCost)
    }
    val toDate = totals(spent, areaHa)
    val seasonTotals = totals(season, areaHa)
    val revenue = sheet.revenue
    return ParcelEconomicsResult(
        areaHa = areaHa,
        seeds = seeds,
        fertilizers = fertilizers,
        chemicals = chemicals,
        misc = misc,
        operations = operations,
        projected = projected,
        recordedTotal = round2(recordedTotal),
        toDate = toDate,
        season = seasonTotals,
        expected = profit(areaHa, revenue.expectedYieldTHa, revenue.pricePerT, seasonTotals.total),
        actual = profit(areaHa, revenue.actualYieldTHa, revenue.actualPricePerT ?: revenue.pricePerT, seasonTotals.total),
    )
}

// ------------------------------------------------------------------ farm

@Serializable
data class FarmParcelEconomics(
    val parcelId: String,
    val name: String,
    val crop: CropCode?,
    val cropName: String?,
    val tillage: TillageSystem?,
    val result: ParcelEconomicsResult,
)

@Serializable
data class EconomicGroup(
    val key: String,
    val label: String,
    val areaHa: Double,
    val cost: Double,
    val costPerHa: Double,
    val fuelL: Double,
    val fuelLPerHa: Double,
    val passes: Double,
    val expectedProfit: Double?,
    val expectedProfitPerHa: Double?,
    val actualProfit: Double?,
    val actualProfitPerHa: Double?,
)

@Serializable
data class FarmEconomics(
    val areaHa: Double,
    val cost: Double,
    val costPerHa: Double,
    val byCategory: Map<EconomicCategory, Double>,
    val expectedProfit: Double?,
    val actualProfit: Double?,
    /** Best available profit per hectare: actual where known, otherwise expected. */
    val profitPerHa: Double?,
    val byParcel: List<EconomicGroup>,
    val byCrop: List<EconomicGroup>,
    val byTillage: List<EconomicGroup>,
    val mostProfitableParcel: EconomicGroup?,
    val mostProfitableCrop: EconomicGroup?,
    val mostEfficientTillage: EconomicGroup?,
)

private fun group(key: String, label: String, items: List<FarmParcelEconomics>): EconomicGroup {
    val areaHa = items.sumOf { it.result.areaHa }
    val cost = items.sumOf { it.result.season.total }
    val fuelL = items.sumOf { item -> (item.result.operations + item.result.projected).sumOf { it.fuelL } }
    val passes = if (areaHa > 0) {
        items.sumOf { (it.result.operations.size + it.result.projected.size) * it.result.areaHa } / areaHa
    } else {
        0.0
    }
    fun sumOf(pick: (FarmParcelEconomics) -> ProfitResult?): Double? {
        val known = items.mapNotNull(pick)
        return if (known.isNotEmpty()) known.sumOf { it.profit } else null
    }
    fun areaOf(pick: (FarmParcelEconomics) -> ProfitResult?) = items.filter { pick(it) != null }.sumOf { it.result.areaHa }
    val expectedProfit = sumOf { it.result.expected }
    val actualProfit = sumOf { it.result.actual }
    val expectedArea = areaOf { it.result.expected }
    val actualArea = areaOf { it.result.actual }
    return EconomicGroup(
        key = key,
        label = label,
        areaHa = round2(areaHa),
        cost = round2(cost),
        costPerHa = if (areaHa > 0) round2(cost / areaHa) else 0.0,
        fuelL = jsRound(fuelL),
        fuelLPerHa = if (areaHa > 0) round1(fuelL / areaHa) else 0.0,
        passes = round1(passes),
        expectedProfit = expectedProfit?.let(::round2),
        expectedProfitPerHa = if (expectedProfit == null || expectedArea == 0.0) null else round2(expectedProfit / expectedArea),
        actualProfit = actualProfit?.let(::round2),
        actualProfitPerHa = if (actualProfit == null || actualArea == 0.0) null else round2(actualProfit / actualArea),
    )
}

private fun bestProfitPerHa(item: EconomicGroup) = item.actualProfitPerHa ?: item.expectedProfitPerHa

private fun groupBy(parcels: List<FarmParcelEconomics>, keyOf: (FarmParcelEconomics) -> Pair<String, String>?): List<EconomicGroup> {
    val groups = linkedMapOf<String, Pair<String, MutableList<FarmParcelEconomics>>>()
    for (item in parcels) {
        val (key, label) = keyOf(item) ?: continue
        groups.getOrPut(key) { label to mutableListOf() }.second.add(item)
    }
    return groups.map { (key, entry) -> group(key, entry.first, entry.second) }.sortedByDescending { it.cost }
}

private fun best(groups: List<EconomicGroup>, score: (EconomicGroup) -> Double?): EconomicGroup? {
    var winner: EconomicGroup? = null
    var top = Double.NEGATIVE_INFINITY
    for (item in groups) {
        val value = score(item)
        if (value != null && value > top) {
            top = value
            winner = item
        }
    }
    return winner
}

fun summarizeFarmEconomics(parcels: List<FarmParcelEconomics>): FarmEconomics {
    val all = group("farm", "Farm", parcels)
    val byCategory = emptyCategories()
    for (item in parcels) for (category in ECONOMIC_CATEGORIES) byCategory.add(category, item.result.season.byCategory.getValue(category))
    val byParcel = parcels.map { group(it.parcelId, it.name, listOf(it)) }.sortedByDescending { it.cost }
    val byCrop = groupBy(parcels) { item -> item.crop?.let { it.name to (item.cropName ?: it.name) } ?: ("NONE" to "No crop") }
    val byTillage = groupBy(parcels) { item -> item.tillage?.let { it.name to it.profile.name } }
    // The most efficient technology earns the most per hectare; without revenue data, the one that costs the least.
    val efficient = best(byTillage, ::bestProfitPerHa) ?: best(byTillage) { if (it.areaHa > 0) -it.costPerHa else null }
    return FarmEconomics(
        areaHa = all.areaHa,
        cost = all.cost,
        costPerHa = all.costPerHa,
        byCategory = ECONOMIC_CATEGORIES.associateWith { round2(byCategory.getValue(it)) },
        expectedProfit = all.expectedProfit,
        actualProfit = all.actualProfit,
        profitPerHa = bestProfitPerHa(all),
        byParcel = byParcel,
        byCrop = byCrop.filter { it.key != "NONE" || it.areaHa > 0 },
        byTillage = byTillage,
        mostProfitableParcel = best(byParcel, ::bestProfitPerHa),
        mostProfitableCrop = best(byCrop.filter { it.key != "NONE" }, ::bestProfitPerHa),
        mostEfficientTillage = efficient,
    )
}

// ------------------------------------------------------------------ advice

data class EconomicInsight(
    val tone: AdviceTone,
    val message: String,
    /** Estimated saving for the parcel, in euro, when the advice is followed. */
    val savings: Double? = null,
)

/** Plain-language findings: where the money goes and what could be cheaper. */
fun economicInsights(
    result: ParcelEconomicsResult,
    tillage: TillageSystem? = null,
    tillageSettings: TillageSettings = emptyMap(),
): List<EconomicInsight> {
    val insights = mutableListOf<EconomicInsight>()
    val season = result.season
    if (season.total <= 0) return listOf(EconomicInsight(AdviceTone.INFO, "Add seed, fertiliser and crop protection to see the real cost per hectare."))

    // The largest category, when it dominates.
    val (topCategory, topValue) = ECONOMIC_CATEGORIES.map { it to season.byCategory.getValue(it) }.sortedByDescending { it.second }.first()
    val share = jsRoundInt((topValue / season.total) * 100)
    if (share >= 25) {
        insights.add(EconomicInsight(if (share >= 40) AdviceTone.WARNING else AdviceTone.INFO, "${topCategory.label} is $share% of the total production cost."))
    }

    // The most expensive field operation.
    val priciest = (result.operations + result.projected).sortedByDescending { it.costPerHa }.firstOrNull()
    if (priciest != null && priciest.costPerHa > 0) {
        insights.add(
            EconomicInsight(
                AdviceTone.INFO,
                "${priciest.label} is the most expensive operation: ${formatEuro(priciest.costPerHa, 2)}/ha${if (priciest.projected) " (estimated)" else ""}.",
            ),
        )
    }

    // A cheaper tillage technology.
    if (tillage != null) {
        val next = TILLAGE_CODES.getOrNull(TILLAGE_CODES.indexOf(tillage) + 1)
        if (next != null) {
            val current = tillageCostPerHa(tillage, tillageSettings)
            val alternative = tillageCostPerHa(next, tillageSettings)
            val pct = if (current > 0) jsRoundInt(((current - alternative) / current) * 100) else 0
            if (pct > 0) {
                insights.add(
                    EconomicInsight(
                        AdviceTone.SUCCESS,
                        "Applying ${next.profile.name} on this parcel can reduce costs by about $pct%.",
                        round2((season.total * pct) / 100),
                    ),
                )
            }
        }
    } else {
        insights.add(EconomicInsight(AdviceTone.INFO, "Set the tillage technology to compare the cost of Minimum Till, Strip Till and No Till."))
    }

    val fuelPerHa = if (result.areaHa > 0) season.byCategory.getValue(EconomicCategory.FUEL) / result.areaHa else 0.0
    if (fuelPerHa > 90) {
        insights.add(EconomicInsight(AdviceTone.WARNING, "Diesel costs ${formatEuro(fuelPerHa, 0)}/ha. Fewer passes or combined operations would lower it."))
    }

    val fertPerHa = if (result.areaHa > 0) season.byCategory.getValue(EconomicCategory.FERTILIZERS) / result.areaHa else 0.0
    if (fertPerHa > 300) {
        insights.add(EconomicInsight(AdviceTone.WARNING, "Fertiliser costs ${formatEuro(fertPerHa, 0)}/ha. A soil analysis can show where the dose can be reduced."))
    }

    val outlook = result.actual ?: result.expected
    if (outlook != null) {
        if (outlook.profit < 0) {
            val breakEven = if (outlook.yieldTHa > 0) season.perHa / outlook.yieldTHa else null
            val breakEvenText = if (breakEven != null && breakEven != 0.0) " It breaks even at ${formatEuro(breakEven, 0)}/t." else ""
            insights.add(EconomicInsight(AdviceTone.WARNING, "The season runs at a loss of ${formatEuro(-outlook.profitPerHa, 0)}/ha.$breakEvenText"))
        } else {
            insights.add(EconomicInsight(AdviceTone.SUCCESS, "Profit of ${formatEuro(outlook.profitPerHa, 0)}/ha, a ${jsNum(outlook.marginPct ?: 0.0)}% margin."))
        }
    } else {
        insights.add(EconomicInsight(AdviceTone.INFO, "Enter the expected yield and the sale price to see the profit."))
    }
    return insights
}

// ------------------------------------------------------------------ stored documents

private fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
private fun JsonElement?.str(max: Int = 120): String = (this as? JsonPrimitive)?.takeIf { it.isString }?.content?.take(max) ?: ""
private fun JsonElement?.strOrNull(max: Int): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content?.take(max)
private fun JsonElement?.lineId(index: Int): String = strOrNull(64)?.takeIf { it.isNotEmpty() } ?: "line-$index"

private fun <T> list(value: JsonElement?, map: (JsonObject, Int) -> T?): List<T> {
    if (value !is JsonArray) return emptyList()
    return value.take(50).mapIndexedNotNull { index, item -> (item as? JsonObject)?.let { map(it, index) } }
}

/** Reads a stored sheet, keeping only well-formed lines. */
fun parseSheet(raw: JsonElement?): EconomicSheet {
    val data = raw as? JsonObject ?: JsonObject(emptyMap())
    val revenue = data["revenue"] as? JsonObject ?: JsonObject(emptyMap())
    return EconomicSheet(
        seeds = list(data["seeds"]) { item, index ->
            SeedLine(
                id = item["id"].lineId(index),
                supplier = item["supplier"].str(),
                hybrid = item["hybrid"].str(),
                category = item["category"].strOrNull(40),
                unit = if (item["unit"].str() == "KG") SeedUnit.KG else SeedUnit.SEEDS,
                bagSize = item["bagSize"].num() ?: 1.0,
                ratePerHa = item["ratePerHa"].num() ?: 0.0,
                pricePerBag = item["pricePerBag"].num() ?: 0.0,
                bagsUsed = item["bagsUsed"].num(),
            )
        },
        fertilizers = list(data["fertilizers"]) { item, index ->
            FertilizerLine(
                id = item["id"].lineId(index),
                product = item["product"].str(),
                ratePerHaKg = item["ratePerHaKg"].num() ?: 0.0,
                pricePerT = item["pricePerT"].num() ?: 0.0,
                applications = item["applications"].num() ?: 1.0,
                n = item["n"].num(),
                p = item["p"].num(),
                k = item["k"].num(),
            )
        },
        chemicals = list(data["chemicals"]) { item, index ->
            ChemicalLine(
                id = item["id"].lineId(index),
                product = item["product"].str(),
                kind = ChemicalKind.entries.firstOrNull { it.name == item["kind"].str() } ?: ChemicalKind.HERBICIDE,
                dosePerHa = item["dosePerHa"].num() ?: 0.0,
                unit = if (item["unit"].str() == "KG") DoseUnit.KG else DoseUnit.L,
                pricePerUnit = item["pricePerUnit"].num() ?: 0.0,
                applications = item["applications"].num() ?: 1.0,
            )
        },
        misc = list(data["misc"]) { item, index ->
            MiscLine(
                id = item["id"].lineId(index),
                label = item["label"].str(),
                amount = item["amount"].num() ?: 0.0,
                basis = if (item["basis"].str() == "PER_HA") MiscBasis.PER_HA else MiscBasis.TOTAL,
            )
        },
        revenue = RevenueInputs(
            expectedYieldTHa = revenue["expectedYieldTHa"].num(),
            actualYieldTHa = revenue["actualYieldTHa"].num(),
            pricePerT = revenue["pricePerT"].num(),
            actualPricePerT = revenue["actualPricePerT"].num(),
        ),
    )
}

/** Reads stored settings, filling in the defaults. */
fun parseSettings(raw: JsonElement?): EconomicSettings {
    val data = raw as? JsonObject ?: JsonObject(emptyMap())
    fun <T> record(value: JsonElement?, map: (JsonObject) -> T): Map<String, T> {
        if (value !is JsonObject) return emptyMap()
        return value.entries.take(500).mapNotNull { (key, item) -> (item as? JsonObject)?.let { key to map(it) } }.toMap()
    }
    return EconomicSettings(
        fuelPricePerL = data["fuelPricePerL"].num() ?: DEFAULT_FUEL_PRICE_EUR,
        defaultLaborRate = data["defaultLaborRate"].num() ?: DEFAULT_LABOR_RATE_EUR_H,
        labor = record(data["labor"]) { LaborProfile(it["monthlySalary"].num(), it["hourlyRate"].num()) },
        machines = record(data["machines"]) {
            MachineCostProfile(
                purchasePrice = it["purchasePrice"].num() ?: 0.0,
                residualPct = it["residualPct"].num() ?: 20.0,
                depreciationYears = it["depreciationYears"].num() ?: 10.0,
                annualHours = it["annualHours"].num() ?: 500.0,
                serviceCostPerYear = it["serviceCostPerYear"].num() ?: 0.0,
                maintenanceCostPerYear = it["maintenanceCostPerYear"].num() ?: 0.0,
            )
        },
    )
}

// ------------------------------------------------------------------ presets

val SEED_SUPPLIERS: Map<CropCode, List<String>> = mapOf(
    CropCode.CORN to listOf("Pioneer", "Dekalb", "KWS", "Syngenta", "Limagrain"),
    CropCode.SUNFLOWER to listOf("Corteva", "Syngenta", "LG", "Euralis", "Pioneer"),
    CropCode.WHEAT to listOf("Own seed", "INCDA Fundulea", "Saaten Union", "RAGT", "Limagrain"),
    CropCode.BARLEY to listOf("Own seed", "Saaten Union", "KWS", "Limagrain"),
    CropCode.RAPESEED to listOf("Dekalb", "Pioneer", "KWS", "LG"),
    CropCode.SOY to listOf("Pioneer", "Euralis", "Saatbau", "Agroser"),
)

data class SeedDefaults(val unit: SeedUnit, val bagSize: Double, val ratePerHa: Double, val pricePerBag: Double)

/** Sensible starting values when adding seed for a crop. */
val SEED_DEFAULTS: Map<CropCode, SeedDefaults> = mapOf(
    CropCode.CORN to SeedDefaults(SeedUnit.SEEDS, 80_000.0, 70_000.0, 180.0),
    CropCode.SUNFLOWER to SeedDefaults(SeedUnit.SEEDS, 150_000.0, 60_000.0, 210.0),
    CropCode.RAPESEED to SeedDefaults(SeedUnit.SEEDS, 1_500_000.0, 500_000.0, 160.0),
    CropCode.SOY to SeedDefaults(SeedUnit.SEEDS, 150_000.0, 550_000.0, 70.0),
    CropCode.WHEAT to SeedDefaults(SeedUnit.KG, 25.0, 220.0, 18.0),
    CropCode.BARLEY to SeedDefaults(SeedUnit.KG, 25.0, 190.0, 16.0),
)

val SEED_CATEGORIES = listOf("Basic", "C1", "C2", "Certified", "Own seed")

/** Presets are lines without an id; the id is given when the line is added to a sheet. */
val FERTILIZER_PRESETS: List<FertilizerLine> = listOf(
    FertilizerLine("", "Urea 46%", 150.0, 430.0, null, 46.0, 0.0, 0.0),
    FertilizerLine("", "Ammonium nitrate 34%", 200.0, 380.0, null, 34.0, 0.0, 0.0),
    FertilizerLine("", "DAP 18-46-0", 150.0, 650.0, null, 18.0, 46.0, 0.0),
    FertilizerLine("", "NPK 15-15-15", 250.0, 520.0, null, 15.0, 15.0, 15.0),
    FertilizerLine("", "Ammonium sulphate", 150.0, 260.0, null, 21.0, 0.0, 0.0),
    FertilizerLine("", "Calcium ammonium nitrate 27%", 200.0, 340.0, null, 27.0, 0.0, 0.0),
)

val CHEMICAL_PRESETS: List<ChemicalLine> = listOf(
    ChemicalLine("", "Adengo", ChemicalKind.HERBICIDE, 0.44, DoseUnit.L, 95.0, null),
    ChemicalLine("", "Laudis", ChemicalKind.HERBICIDE, 2.0, DoseUnit.L, 26.0, null),
    ChemicalLine("", "Elumis", ChemicalKind.HERBICIDE, 1.5, DoseUnit.L, 32.0, null),
    ChemicalLine("", "Pictor", ChemicalKind.FUNGICIDE, 0.5, DoseUnit.L, 78.0, null),
    ChemicalLine("", "Amistar", ChemicalKind.FUNGICIDE, 1.0, DoseUnit.L, 48.0, null),
    ChemicalLine("", "Karate Zeon", ChemicalKind.INSECTICIDE, 0.15, DoseUnit.L, 60.0, null),
    ChemicalLine("", "Moddus", ChemicalKind.GROWTH_REGULATOR, 0.4, DoseUnit.L, 55.0, null),
)
