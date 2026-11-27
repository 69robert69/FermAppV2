package com.fermapp.api.domain.economics

import com.fermapp.api.domain.model.CostCategory
import com.fermapp.api.domain.model.CropCode
import com.fermapp.api.domain.tillage.Tillage.TillageSystem

/** Data types of the economics module (economics.ts). They are also the JSON shapes. */
object EconomicModels {

    /** The economic categories of the production cost, in display order. */
    enum class EconomicCategory { SEEDS, FERTILIZERS, CHEMICALS, FUEL, MACHINERY, LABOR, OTHER }

    enum class ChemicalKind { HERBICIDE, FUNGICIDE, INSECTICIDE, GROWTH_REGULATOR }

    /**
     * A seed line of the sheet.
     *
     * @param category seed category for cereals, for example Basic, C1 or C2
     * @param unit corn and sunflower are sold by seeds per bag (SEEDS), cereals by kilograms (KG)
     * @param bagSize seeds or kilograms in one bag
     * @param bagsUsed bags actually used; when empty the need is computed from the rate
     */
    @JvmRecord
    data class SeedLine(
        val id: String,
        val supplier: String,
        val hybrid: String,
        val category: String?,
        val unit: String,
        val bagSize: Double,
        val ratePerHa: Double,
        val pricePerBag: Double,
        val bagsUsed: Double?,
    )

    /** A fertiliser line; n, p and k are the nutrient content in percent. */
    @JvmRecord
    data class FertilizerLine(
        val id: String,
        val product: String,
        val ratePerHaKg: Double,
        val pricePerT: Double,
        val applications: Double,
        val n: Double?,
        val p: Double?,
        val k: Double?,
    )

    @JvmRecord
    data class ChemicalLine(
        val id: String,
        val product: String,
        val kind: ChemicalKind,
        val dosePerHa: Double,
        val unit: String,
        val pricePerUnit: Double,
        val applications: Double,
    )

    /** Another cost; PER_HA multiplies by the area, TOTAL is the amount for the whole parcel. */
    @JvmRecord
    data class MiscLine(val id: String, val label: String, val amount: Double, val basis: String)

    /** Revenue inputs; [actualPricePerT] is the price obtained, when it differs from the planned one. */
    @JvmRecord
    data class RevenueInputs(
        val expectedYieldTHa: Double?,
        val actualYieldTHa: Double?,
        val pricePerT: Double?,
        val actualPricePerT: Double?,
    )

    @JvmRecord
    data class EconomicSheet(
        val seeds: List<SeedLine>,
        val fertilizers: List<FertilizerLine>,
        val chemicals: List<ChemicalLine>,
        val misc: List<MiscLine>,
        val revenue: RevenueInputs,
    )

    /** Ownership costs of a machine; [residualPct] is the value left at the end, in percent. */
    @JvmRecord
    data class MachineCostProfile(
        val purchasePrice: Double,
        val residualPct: Double,
        val depreciationYears: Double,
        val annualHours: Double,
        val serviceCostPerYear: Double,
        val maintenanceCostPerYear: Double,
    )

    @JvmRecord
    data class LaborProfile(val monthlySalary: Double?, val hourlyRate: Double?)

    @JvmRecord
    data class EconomicSettings(
        val fuelPricePerL: Double,
        val defaultLaborRate: Double,
        val labor: Map<String, LaborProfile>,
        val machines: Map<String, MachineCostProfile>,
    )

    @JvmRecord
    data class SeedCost(
        val line: SeedLine,
        val costPerHa: Double,
        val costPerKg: Double?,
        val costPer1000Seeds: Double?,
        val bagsNeeded: Double,
        val total: Double,
    )

    @JvmRecord
    data class Nutrients(val n: Double, val p: Double, val k: Double)

    @JvmRecord
    data class FertilizerCost(val line: FertilizerLine, val pricePerKg: Double, val costPerHa: Double, val total: Double, val nutrients: Nutrients)

    @JvmRecord
    data class ChemicalCost(val line: ChemicalLine, val costPerHa: Double, val total: Double, val quantity: Double)

    @JvmRecord
    data class MiscCost(val line: MiscLine, val total: Double)

    @JvmRecord
    data class MachineHourlyCost(
        val perHour: Double,
        val depreciation: Double,
        val service: Double,
        val maintenance: Double,
        val estimated: Boolean,
    )

    @JvmRecord
    data class OperationMachine(val id: String?, val name: String?, val type: String?, val fuelLPerH: Double?)

    @JvmRecord
    data class OperationOperator(val id: String?, val name: String?)

    /**
     * A logged field operation.
     *
     * @param areaHa planned area of the operation
     * @param hours hours actually worked so far
     */
    @JvmRecord
    data class OperationInput(
        val id: String?,
        val type: String,
        val status: String?,
        val areaHa: Double,
        val progressPct: Double,
        val hours: Double,
        val fuelLiters: Double?,
        val machine: OperationMachine?,
        val operator: OperationOperator?,
        val startedAt: String?,
    )

    /** A priced operation. [projected]: not in the work log yet; [estimated]: typical values were used. */
    @JvmRecord
    data class OperationCost(
        val id: String?,
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
        val projected: Boolean,
        val estimated: Boolean,
        val startedAt: String?,
    )

    @JvmRecord
    data class CostTotals(val byCategory: Map<EconomicCategory, Double>, val total: Double, val perHa: Double)

    @JvmRecord
    data class ProfitResult(
        val yieldTHa: Double,
        val pricePerT: Double,
        val revenue: Double,
        val revenuePerHa: Double,
        val profit: Double,
        val profitPerHa: Double,
        val marginPct: Double?,
    )

    @JvmRecord
    data class RecordedCost(val category: CostCategory, val amount: Double)

    /** Economics of one parcel; [toDate] is what was spent, [season] adds the operations still to come. */
    @JvmRecord
    data class ParcelEconomicsResult(
        val areaHa: Double,
        val seeds: List<SeedCost>,
        val fertilizers: List<FertilizerCost>,
        val chemicals: List<ChemicalCost>,
        val misc: List<MiscCost>,
        val operations: List<OperationCost>,
        val projected: List<OperationCost>,
        val recordedTotal: Double,
        val toDate: CostTotals,
        val season: CostTotals,
        val expected: ProfitResult?,
        val actual: ProfitResult?,
    )

    @JvmRecord
    data class FarmParcelEconomics(
        val parcelId: String?,
        val name: String?,
        val crop: CropCode?,
        val cropName: String?,
        val tillage: TillageSystem?,
        val result: ParcelEconomicsResult,
    )

    @JvmRecord
    data class EconomicGroup(
        val key: String?,
        val label: String?,
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

    /** [profitPerHa]: the best available profit per hectare, actual where known, otherwise expected. */
    @JvmRecord
    data class FarmEconomics(
        val areaHa: Double,
        val cost: Double,
        val costPerHa: Double,
        val byCategory: Map<EconomicCategory, Double>,
        val expectedProfit: Double?,
        val actualProfit: Double?,
        val profitPerHa: Double?,
        val byParcel: List<EconomicGroup>,
        val byCrop: List<EconomicGroup>,
        val byTillage: List<EconomicGroup>,
        val mostProfitableParcel: EconomicGroup?,
        val mostProfitableCrop: EconomicGroup?,
        val mostEfficientTillage: EconomicGroup?,
    )

    /** A plain-language finding; [savings] is the estimated saving in euro, when the advice is followed. */
    @JvmRecord
    data class EconomicInsight(val tone: String, val message: String, val savings: Double?)
}
