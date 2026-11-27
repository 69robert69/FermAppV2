package com.fermapp.api.domain.economics

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.MissingNode
import com.fermapp.api.common.util.Js
import com.fermapp.api.domain.economics.EconomicModels.ChemicalCost
import com.fermapp.api.domain.economics.EconomicModels.ChemicalKind
import com.fermapp.api.domain.economics.EconomicModels.ChemicalLine
import com.fermapp.api.domain.economics.EconomicModels.CostTotals
import com.fermapp.api.domain.economics.EconomicModels.EconomicCategory
import com.fermapp.api.domain.economics.EconomicModels.EconomicGroup
import com.fermapp.api.domain.economics.EconomicModels.EconomicInsight
import com.fermapp.api.domain.economics.EconomicModels.EconomicSettings
import com.fermapp.api.domain.economics.EconomicModels.EconomicSheet
import com.fermapp.api.domain.economics.EconomicModels.FarmEconomics
import com.fermapp.api.domain.economics.EconomicModels.FarmParcelEconomics
import com.fermapp.api.domain.economics.EconomicModels.FertilizerCost
import com.fermapp.api.domain.economics.EconomicModels.FertilizerLine
import com.fermapp.api.domain.economics.EconomicModels.LaborProfile
import com.fermapp.api.domain.economics.EconomicModels.MachineCostProfile
import com.fermapp.api.domain.economics.EconomicModels.MachineHourlyCost
import com.fermapp.api.domain.economics.EconomicModels.MiscCost
import com.fermapp.api.domain.economics.EconomicModels.MiscLine
import com.fermapp.api.domain.economics.EconomicModels.Nutrients
import com.fermapp.api.domain.economics.EconomicModels.OperationCost
import com.fermapp.api.domain.economics.EconomicModels.OperationInput
import com.fermapp.api.domain.economics.EconomicModels.ParcelEconomicsResult
import com.fermapp.api.domain.economics.EconomicModels.ProfitResult
import com.fermapp.api.domain.economics.EconomicModels.RecordedCost
import com.fermapp.api.domain.economics.EconomicModels.RevenueInputs
import com.fermapp.api.domain.economics.EconomicModels.SeedCost
import com.fermapp.api.domain.economics.EconomicModels.SeedLine
import com.fermapp.api.domain.model.CostCategory
import com.fermapp.api.domain.model.CropCode
import com.fermapp.api.domain.sumOfDoubles
import com.fermapp.api.domain.tillage.Tillage
import com.fermapp.api.domain.tillage.Tillage.TillageSystem
import java.util.EnumMap
import kotlin.math.max
import kotlin.math.min

/**
 * Real production cost per hectare, in euro (economics.ts).
 *
 * Inputs come from the parcel's economic sheet. Field operations come from the work log:
 * each one is costed for diesel, machine ownership and labour. Operations the crop still needs
 * are projected, so the cost per hectare is realistic from the first day of the season.
 */
object Economics {

    @JvmField
    val LABELS: Map<EconomicCategory, String> = EnumMap(
        mapOf(
            EconomicCategory.SEEDS to "Seeds",
            EconomicCategory.FERTILIZERS to "Fertilisers",
            EconomicCategory.CHEMICALS to "Crop protection",
            EconomicCategory.FUEL to "Diesel",
            EconomicCategory.MACHINERY to "Machinery",
            EconomicCategory.LABOR to "Labour",
            EconomicCategory.OTHER to "Other costs",
        ),
    )

    /** Expenses recorded by category land in the matching economic category. */
    @JvmField
    val RECORDED_TO_ECONOMIC: Map<CostCategory, EconomicCategory> = EnumMap(
        mapOf(
            CostCategory.SEED to EconomicCategory.SEEDS,
            CostCategory.FERTILIZER to EconomicCategory.FERTILIZERS,
            CostCategory.PESTICIDE to EconomicCategory.CHEMICALS,
            CostCategory.FUEL to EconomicCategory.FUEL,
            CostCategory.LABOR to EconomicCategory.LABOR,
            CostCategory.IRRIGATION to EconomicCategory.OTHER,
            CostCategory.OTHER to EconomicCategory.OTHER,
        ),
    )

    const val DEFAULT_LABOR_RATE_EUR_H = 10.0

    /** Working hours in a month, used to turn a monthly salary into an hourly rate. */
    const val HOURS_PER_MONTH = 168.0

    /** Typical ownership and upkeep per hour when the machine's own figures are missing. */
    @JvmField
    val DEFAULT_MACHINE_COST_EUR_H: Map<String, Double> = mapOf(
        "TRACTOR" to 35.0, "COMBINE" to 120.0, "SPRAYER" to 45.0, "SEEDER" to 20.0,
        "DISC" to 12.0, "CULTIVATOR" to 12.0, "SUBSOILER" to 14.0, "TRAILER" to 6.0,
    )
    private const val FALLBACK_MACHINE_COST_EUR_H = 35.0

    /** Typical diesel per hectare when neither the log nor the machine says more. */
    @JvmField
    val DEFAULT_FUEL_L_PER_HA: Map<String, Double> = mapOf(
        "PLOWING" to 20.0, "DISCING" to 9.0, "SUBSOILING" to 15.0, "SOWING" to 6.0,
        "FERTILIZING" to 2.0, "SPRAYING" to 1.5, "HARVESTING" to 16.0,
    )

    @JvmField
    val WORK_OPERATION_LABELS: Map<String, String> = mapOf(
        "PLOWING" to "Ploughing", "DISCING" to "Discing", "SUBSOILING" to "Subsoiling", "SOWING" to "Sowing",
        "FERTILIZING" to "Fertilising", "SPRAYING" to "Spraying", "HARVESTING" to "Harvesting",
    )

    private fun positive(value: Double?, fallback: Double = 0.0): Double =
        if (value != null && value.isFinite() && value >= 0) value else fallback

    // ------------------------------------------------------------------ line costs

    @JvmStatic
    fun seedCost(line: SeedLine, areaHa: Double): SeedCost {
        val bag = positive(line.bagSize, 1.0).let { if (it == 0.0) 1.0 else it }
        val perUnit = positive(line.pricePerBag) / bag
        val costPerHa = perUnit * positive(line.ratePerHa)
        val bagsNeeded = positive(line.ratePerHa) * areaHa / bag
        val bagsUsed = line.bagsUsed
        val total = if (bagsUsed != null && bagsUsed >= 0) bagsUsed * positive(line.pricePerBag) else costPerHa * areaHa
        return SeedCost(
            line, Js.round2(costPerHa),
            if (line.unit == "KG") Js.round2(perUnit) else null,
            if (line.unit == "SEEDS") Js.round2(perUnit * 1000) else null,
            Js.round1(bagsNeeded), Js.round2(total),
        )
    }

    @JvmStatic
    fun fertilizerCost(line: FertilizerLine, areaHa: Double): FertilizerCost {
        val applications = max(1.0, positive(line.applications, 1.0))
        val kgPerHa = positive(line.ratePerHaKg) * applications
        val pricePerKg = positive(line.pricePerT) / 1000
        val costPerHa = kgPerHa * pricePerKg
        return FertilizerCost(
            line, Js.round2(pricePerKg * 1000) / 1000, Js.round2(costPerHa), Js.round2(costPerHa * areaHa),
            Nutrients(
                Js.round(kgPerHa * positive(line.n) / 100),
                Js.round(kgPerHa * positive(line.p) / 100),
                Js.round(kgPerHa * positive(line.k) / 100),
            ),
        )
    }

    @JvmStatic
    fun chemicalCost(line: ChemicalLine, areaHa: Double): ChemicalCost {
        val applications = max(1.0, positive(line.applications, 1.0))
        val perHa = positive(line.dosePerHa) * positive(line.pricePerUnit) * applications
        return ChemicalCost(line, Js.round2(perHa), Js.round2(perHa * areaHa), Js.round1(positive(line.dosePerHa) * applications * areaHa))
    }

    @JvmStatic
    fun miscCost(line: MiscLine, areaHa: Double): Double =
        Js.round2(if (line.basis == "PER_HA") positive(line.amount) * areaHa else positive(line.amount))

    // ------------------------------------------------------------------ machines and labour

    @JvmStatic
    fun machineHourlyCost(profile: MachineCostProfile?, machineType: String?): MachineHourlyCost {
        if (profile == null || !(profile.annualHours > 0) || !(profile.depreciationYears > 0)) {
            val perHour = DEFAULT_MACHINE_COST_EUR_H[machineType ?: ""] ?: FALLBACK_MACHINE_COST_EUR_H
            return MachineHourlyCost(perHour, perHour, 0.0, 0.0, true)
        }
        val residual = positive(profile.residualPct, 20.0) / 100
        val depreciation = positive(profile.purchasePrice) * (1 - residual) / profile.depreciationYears / profile.annualHours
        val service = positive(profile.serviceCostPerYear) / profile.annualHours
        val maintenance = positive(profile.maintenanceCostPerYear) / profile.annualHours
        return MachineHourlyCost(
            Js.round2(depreciation + service + maintenance), Js.round2(depreciation),
            Js.round2(service), Js.round2(maintenance), false,
        )
    }

    @JvmStatic
    fun laborHourlyRate(profile: LaborProfile?, fallback: Double): Double {
        val hourly = profile?.hourlyRate
        if (hourly != null && hourly > 0) {
            return hourly
        }
        val monthly = profile?.monthlySalary
        if (monthly != null && monthly > 0) {
            return Js.round2(monthly / HOURS_PER_MONTH)
        }
        return fallback
    }

    // ------------------------------------------------------------------ operations

    private fun typicalFuel(type: String): Double = DEFAULT_FUEL_L_PER_HA[type] ?: 5.0

    /** Diesel per hectare for an operation, following the tillage technology when one is set. */
    @JvmStatic
    fun fuelPerHaFor(type: String, tillage: TillageSystem?): Double {
        val typical = typicalFuel(type)
        if (tillage == null) {
            return typical
        }
        val profile = Tillage.profile(tillage)
        val tillageTypes = profile.operations.map { it.workType }
        if (type !in tillageTypes) {
            return typical
        }
        // The technology's diesel per hectare is shared between its operations by their typical weight.
        val total = tillageTypes.sumOfDoubles(::typicalFuel)
        return Js.round1(profile.fuelLPerHa * typicalFuel(type) / total)
    }

    private class PricingBase(
        val id: String?,
        val type: String,
        val areaHa: Double,
        val hours: Double,
        val fuelL: Double,
        val machineType: String?,
        val machineId: String?,
        val operatorId: String?,
    )

    private class PricingExtra(
        val machineName: String?,
        val operatorName: String?,
        val projected: Boolean,
        val estimated: Boolean,
        val startedAt: String?,
    )

    private fun priceOperation(base: PricingBase, settings: EconomicSettings, extra: PricingExtra): OperationCost {
        val machine = machineHourlyCost(base.machineId?.let { settings.machines[it] }, base.machineType ?: "TRACTOR")
        val labor = laborHourlyRate(base.operatorId?.let { settings.labor[it] }, settings.defaultLaborRate)
        val fuelCost = base.fuelL * settings.fuelPricePerL
        val machineCost = machine.perHour * base.hours
        val laborCost = labor * base.hours
        val total = fuelCost + machineCost + laborCost
        return OperationCost(
            base.id, base.type, WORK_OPERATION_LABELS[base.type] ?: base.type,
            extra.machineName, extra.operatorName, Js.round2(base.areaHa), Js.round1(base.hours),
            Js.round(base.fuelL), Js.round2(fuelCost), Js.round2(machineCost), Js.round2(laborCost), Js.round2(total),
            if (base.areaHa > 0) Js.round2(total / base.areaHa) else 0.0, extra.projected,
            extra.estimated || machine.estimated, extra.startedAt,
        )
    }

    /** Prices one logged operation: diesel, machine hours and operator hours. */
    @JvmStatic
    fun costOperation(op: OperationInput, settings: EconomicSettings, tillage: TillageSystem?): OperationCost {
        val areaHa = op.areaHa * min(100.0, max(0.0, op.progressPct)) / 100
        val hours = max(0.0, op.hours)
        val loggedFuel = op.fuelLiters?.takeIf { it > 0 }
        val machineRate = op.machine?.fuelLPerH
        val machineFuel = if (machineRate != null && machineRate != 0.0) machineRate * hours else null
        val fuelL = loggedFuel ?: machineFuel ?: (fuelPerHaFor(op.type, tillage) * areaHa)
        return priceOperation(
            PricingBase(op.id, op.type, areaHa, hours, fuelL, op.machine?.type, op.machine?.id, op.operator?.id),
            settings,
            PricingExtra(op.machine?.name, op.operator?.name, false, loggedFuel == null && machineFuel == null, op.startedAt),
        )
    }

    /** Field capacity of an operation from the implement catalog, in hectares per hour. */
    private fun typicalCapacityHaH(type: String): Double {
        if (type == "HARVESTING") {
            return 6 * 6 * 0.7 / 10
        }
        val implement = ImplementCatalog.forOperation(type).orElse(null) ?: return 2.0
        val width = implement.widthsM[(implement.widthsM.size - 1) / 2]
        return width * implement.speedKmh * (implement.efficiencyPct / 100) / 10
    }

    /** The operations a season needs: the technology's tillage and sowing, fertilising, spraying and harvest. */
    @JvmStatic
    fun seasonOperations(sheet: EconomicSheet, tillage: TillageSystem?, crop: CropCode?): List<String> {
        val types = (tillage?.let { system -> Tillage.profile(system).operations.map { it.workType } } ?: listOf("DISCING", "SOWING"))
            .toMutableList()
        var passes = 0.0
        for (line in sheet.fertilizers) {
            passes = max(passes, max(1.0, line.applications))
        }
        val existing = types.count { it == "FERTILIZING" }
        var i = 0
        while (i < passes - existing) {
            types += "FERTILIZING"
            i++
        }
        val spraying = sheet.chemicals.sumOfDoubles { max(1.0, it.applications) }
        i = 0
        while (i < spraying) {
            types += "SPRAYING"
            i++
        }
        if (crop != null) {
            types += "HARVESTING"
        }
        return types
    }

    /** Estimates the operations still to come, with the typical machine, diesel and labour. */
    @JvmStatic
    fun projectOperations(
        needed: List<String>,
        logged: List<OperationInput>,
        areaHa: Double,
        settings: EconomicSettings,
        tillage: TillageSystem?,
    ): List<OperationCost> {
        val done = LinkedHashMap<String, Double>()
        for (op in logged) {
            if (op.status == "CANCELLED") {
                continue
            }
            done.merge(op.type, min(1.0, op.progressPct / 100)) { a, b -> a + b }
        }
        val projected = mutableListOf<OperationCost>()
        needed.forEachIndexed { index, type ->
            val covered = done[type] ?: 0.0
            val share = max(0.0, min(1.0, 1 - covered))
            done[type] = max(0.0, covered - 1)
            if (share <= 0.01) {
                return@forEachIndexed
            }
            val area = areaHa * share
            val hours = area / typicalCapacityHaH(type)
            projected += priceOperation(
                PricingBase(
                    "projected-$index-$type", type, area, hours, fuelPerHaFor(type, tillage) * area,
                    if (type == "HARVESTING") "COMBINE" else "TRACTOR", null, null,
                ),
                settings,
                PricingExtra(null, null, true, true, null),
            )
        }
        return projected
    }

    // ------------------------------------------------------------------ parcel

    private fun emptyCategories(): EnumMap<EconomicCategory, Double> =
        EnumMap<EconomicCategory, Double>(EconomicCategory::class.java).apply {
            EconomicCategory.entries.forEach { put(it, 0.0) }
        }

    private fun totals(byCategory: Map<EconomicCategory, Double>, areaHa: Double): CostTotals {
        val rounded = EnumMap<EconomicCategory, Double>(EconomicCategory::class.java)
        var sum = 0.0
        for (category in EconomicCategory.entries) {
            val value = byCategory.getValue(category)
            rounded[category] = Js.round2(value)
            sum += value
        }
        val total = Js.round2(sum)
        return CostTotals(rounded, total, if (areaHa > 0) Js.round2(total / areaHa) else 0.0)
    }

    private fun profit(areaHa: Double, yieldTHa: Double?, pricePerT: Double?, cost: Double): ProfitResult? {
        if (yieldTHa == null || pricePerT == null || !(yieldTHa > 0) || !(pricePerT > 0)) {
            return null
        }
        val revenue = yieldTHa * pricePerT * areaHa
        val result = revenue - cost
        return ProfitResult(
            yieldTHa, pricePerT, Js.round2(revenue), Js.round2(yieldTHa * pricePerT), Js.round2(result),
            if (areaHa > 0) Js.round2(result / areaHa) else 0.0,
            if (revenue > 0) Math.round(result / revenue * 1000) / 10.0 else null,
        )
    }

    private fun EnumMap<EconomicCategory, Double>.add(category: EconomicCategory, amount: Double) {
        merge(category, amount) { a, b -> a + b }
    }

    @JvmStatic
    fun computeParcelEconomics(
        areaHa: Double,
        sheet: EconomicSheet,
        operations: List<OperationInput>,
        settings: EconomicSettings,
        tillage: TillageSystem?,
        crop: CropCode?,
        recorded: List<RecordedCost>?,
    ): ParcelEconomicsResult {
        val seeds = sheet.seeds.map { seedCost(it, areaHa) }
        val fertilizers = sheet.fertilizers.map { fertilizerCost(it, areaHa) }
        val chemicals = sheet.chemicals.map { chemicalCost(it, areaHa) }
        val misc = sheet.misc.map { MiscCost(it, miscCost(it, areaHa)) }
        val logged = operations.filter { it.status != "CANCELLED" || it.progressPct > 0 }
        val priced = logged.map { costOperation(it, settings, tillage) }
        val projected = projectOperations(seasonOperations(sheet, tillage, crop), logged, areaHa, settings, tillage)

        val spent = emptyCategories()
        spent.add(EconomicCategory.SEEDS, seeds.sumOfDoubles { it.total })
        spent.add(EconomicCategory.FERTILIZERS, fertilizers.sumOfDoubles { it.total })
        spent.add(EconomicCategory.CHEMICALS, chemicals.sumOfDoubles { it.total })
        spent.add(EconomicCategory.OTHER, misc.sumOfDoubles { it.total })
        for (op in priced) {
            spent.add(EconomicCategory.FUEL, op.fuelCost)
            spent.add(EconomicCategory.MACHINERY, op.machineCost)
            spent.add(EconomicCategory.LABOR, op.laborCost)
        }
        var recordedTotal = 0.0
        for (entry in recorded.orEmpty()) {
            spent.add(RECORDED_TO_ECONOMIC.getValue(entry.category), entry.amount)
            recordedTotal += entry.amount
        }
        val season = EnumMap(spent)
        for (op in projected) {
            season.add(EconomicCategory.FUEL, op.fuelCost)
            season.add(EconomicCategory.MACHINERY, op.machineCost)
            season.add(EconomicCategory.LABOR, op.laborCost)
        }
        val toDate = totals(spent, areaHa)
        val seasonTotals = totals(season, areaHa)
        val revenue = sheet.revenue
        return ParcelEconomicsResult(
            areaHa, seeds, fertilizers, chemicals, misc, priced, projected,
            Js.round2(recordedTotal), toDate, seasonTotals,
            profit(areaHa, revenue.expectedYieldTHa, revenue.pricePerT, seasonTotals.total),
            profit(areaHa, revenue.actualYieldTHa, revenue.actualPricePerT ?: revenue.pricePerT, seasonTotals.total),
        )
    }

    // ------------------------------------------------------------------ farm

    private fun group(key: String?, label: String?, items: List<FarmParcelEconomics>): EconomicGroup {
        val areaHa = items.sumOfDoubles { it.result.areaHa }
        val cost = items.sumOfDoubles { it.result.season.total }
        val fuelL = items.sumOfDoubles { item ->
            item.result.operations.sumOfDoubles { it.fuelL } + item.result.projected.sumOfDoubles { it.fuelL }
        }
        val passes = if (areaHa > 0) {
            items.sumOfDoubles { (it.result.operations.size + it.result.projected.size) * it.result.areaHa } / areaHa
        } else {
            0.0
        }
        val expectedProfit = sumProfit(items) { it.result.expected }
        val actualProfit = sumProfit(items) { it.result.actual }
        val expectedArea = areaOf(items) { it.result.expected }
        val actualArea = areaOf(items) { it.result.actual }
        return EconomicGroup(
            key, label, Js.round2(areaHa), Js.round2(cost), if (areaHa > 0) Js.round2(cost / areaHa) else 0.0,
            Js.round(fuelL), if (areaHa > 0) Js.round1(fuelL / areaHa) else 0.0, Js.round1(passes),
            expectedProfit?.let { Js.round2(it) },
            if (expectedProfit == null || expectedArea == 0.0) null else Js.round2(expectedProfit / expectedArea),
            actualProfit?.let { Js.round2(it) },
            if (actualProfit == null || actualArea == 0.0) null else Js.round2(actualProfit / actualArea),
        )
    }

    private fun sumProfit(items: List<FarmParcelEconomics>, pick: (FarmParcelEconomics) -> ProfitResult?): Double? {
        val known = items.mapNotNull(pick)
        return if (known.isEmpty()) null else known.sumOfDoubles { it.profit }
    }

    private fun areaOf(items: List<FarmParcelEconomics>, pick: (FarmParcelEconomics) -> ProfitResult?): Double =
        items.filter { pick(it) != null }.sumOfDoubles { it.result.areaHa }

    private fun bestProfitPerHa(item: EconomicGroup): Double? = item.actualProfitPerHa ?: item.expectedProfitPerHa

    private fun groupBy(
        parcels: List<FarmParcelEconomics>,
        keyOf: (FarmParcelEconomics) -> Pair<String, String>?,
    ): List<EconomicGroup> {
        val labels = LinkedHashMap<String, String>()
        val groups = LinkedHashMap<String, MutableList<FarmParcelEconomics>>()
        for (item in parcels) {
            val (key, label) = keyOf(item) ?: continue
            labels.putIfAbsent(key, label)
            groups.getOrPut(key) { mutableListOf() } += item
        }
        return groups.map { (key, items) -> group(key, labels[key], items) }.sortedByDescending { it.cost }
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

    @JvmStatic
    fun summarizeFarmEconomics(parcels: List<FarmParcelEconomics>): FarmEconomics {
        val all = group("farm", "Farm", parcels)
        val byCategory = emptyCategories()
        for (item in parcels) {
            for (category in EconomicCategory.entries) {
                byCategory.add(category, item.result.season.byCategory.getValue(category))
            }
        }
        byCategory.replaceAll { _, value -> Js.round2(value) }
        val byParcel = parcels.map { group(it.parcelId, it.name, listOf(it)) }.sortedByDescending { it.cost }
        val byCrop = groupBy(parcels) { item ->
            val crop = item.crop
            if (crop != null) crop.name to (item.cropName ?: crop.name) else "NONE" to "No crop"
        }
        val byTillage = groupBy(parcels) { item -> item.tillage?.let { it.name to Tillage.profile(it).name } }
        // The most efficient technology earns the most per hectare; without revenue data, the one that costs the least.
        val efficient = best(byTillage, ::bestProfitPerHa)
            ?: best(byTillage) { if (it.areaHa > 0) -it.costPerHa else null }
        return FarmEconomics(
            all.areaHa, all.cost, all.costPerHa, byCategory, all.expectedProfit, all.actualProfit,
            bestProfitPerHa(all), byParcel,
            byCrop.filter { it.key != "NONE" || it.areaHa > 0 },
            byTillage, best(byParcel, ::bestProfitPerHa),
            best(byCrop.filter { it.key != "NONE" }, ::bestProfitPerHa),
            efficient,
        )
    }

    // ------------------------------------------------------------------ advice

    /** Plain-language findings: where the money goes and what could be cheaper. */
    @JvmStatic
    fun economicInsights(result: ParcelEconomicsResult, tillage: TillageSystem?): List<EconomicInsight> {
        val season = result.season
        if (season.total <= 0) {
            return listOf(EconomicInsight("info", "Add seed, fertiliser and crop protection to see the real cost per hectare.", null))
        }
        val insights = mutableListOf<EconomicInsight>()

        var topCategory: EconomicCategory? = null
        var topValue = Double.NEGATIVE_INFINITY
        for (category in EconomicCategory.entries) {
            val value = season.byCategory.getValue(category)
            if (value > topValue) {
                topValue = value
                topCategory = category
            }
        }
        val share = Math.round(topValue / season.total * 100)
        if (share >= 25) {
            insights += EconomicInsight(
                if (share >= 40) "warning" else "info",
                "${LABELS[topCategory]} is $share% of the total production cost.", null,
            )
        }

        val priciest = (result.operations + result.projected).sortedByDescending { it.costPerHa }.firstOrNull()
        if (priciest != null && priciest.costPerHa > 0) {
            val estimated = if (priciest.projected) " (estimated)" else ""
            insights += EconomicInsight(
                "info",
                "${priciest.label} is the most expensive operation: ${Money.formatEuro(priciest.costPerHa, 2)}/ha$estimated.", null,
            )
        }

        if (tillage != null) {
            val next = TillageSystem.entries.getOrNull(tillage.ordinal + 1)
            if (next != null) {
                val current = Tillage.costPerHa(tillage)
                val alternative = Tillage.costPerHa(next)
                val pct = if (current > 0) Math.round((current - alternative) / current * 100) else 0L
                if (pct > 0) {
                    insights += EconomicInsight(
                        "success",
                        "Applying ${Tillage.profile(next).name} on this parcel can reduce costs by about $pct%.",
                        Js.round2(season.total * pct / 100),
                    )
                }
            }
        } else {
            insights += EconomicInsight(
                "info", "Set the tillage technology to compare the cost of Minimum Till, Strip Till and No Till.", null,
            )
        }

        val fuelPerHa = if (result.areaHa > 0) season.byCategory.getValue(EconomicCategory.FUEL) / result.areaHa else 0.0
        if (fuelPerHa > 90) {
            insights += EconomicInsight(
                "warning",
                "Diesel costs ${Money.formatEuro(fuelPerHa, 0)}/ha. Fewer passes or combined operations would lower it.", null,
            )
        }
        val fertPerHa = if (result.areaHa > 0) season.byCategory.getValue(EconomicCategory.FERTILIZERS) / result.areaHa else 0.0
        if (fertPerHa > 300) {
            insights += EconomicInsight(
                "warning",
                "Fertiliser costs ${Money.formatEuro(fertPerHa, 0)}/ha. A soil analysis can show where the dose can be reduced.", null,
            )
        }

        val outlook = result.actual ?: result.expected
        insights += when {
            outlook == null -> EconomicInsight("info", "Enter the expected yield and the sale price to see the profit.", null)
            outlook.profit < 0 -> {
                val breakEven = if (outlook.yieldTHa > 0) season.perHa / outlook.yieldTHa else null
                val breakEvenText = if (breakEven != null && breakEven != 0.0) {
                    " It breaks even at ${Money.formatEuro(breakEven, 0)}/t."
                } else {
                    ""
                }
                EconomicInsight("warning", "The season runs at a loss of ${Money.formatEuro(-outlook.profitPerHa, 0)}/ha.$breakEvenText", null)
            }
            else -> EconomicInsight(
                "success",
                "Profit of ${Money.formatEuro(outlook.profitPerHa, 0)}/ha, a ${Js.num(outlook.marginPct ?: 0.0)}% margin.", null,
            )
        }
        return insights
    }

    // ------------------------------------------------------------------ stored documents

    private fun num(value: JsonNode?): Double? =
        if (value != null && value.isNumber && value.asDouble().isFinite()) value.asDouble() else null

    private fun num(value: JsonNode?, fallback: Double): Double = num(value) ?: fallback

    private fun str(value: JsonNode?, max: Int): String =
        if (value == null || !value.isTextual) "" else value.asText().take(max)

    private fun id(value: JsonNode?, index: Int): String =
        if (value != null && value.isTextual && value.asText().isNotEmpty()) value.asText().take(64) else "line-$index"

    /** The first 50 entries of a JSON array that are objects, each mapped with its index. */
    private fun <T> list(value: JsonNode?, mapper: (JsonNode, Int) -> T): List<T> {
        if (value == null || !value.isArray) {
            return mutableListOf()
        }
        return (0 until min(50, value.size()))
            .mapNotNull { index -> value.get(index)?.takeIf { it.isObject }?.let { mapper(it, index) } }
            .toMutableList()
    }

    private fun objectOrMissing(value: JsonNode?): JsonNode =
        if (value != null && value.isObject) value else MissingNode.getInstance()

    /** Reads a stored sheet, keeping only well-formed lines. */
    @JvmStatic
    fun parseSheet(raw: JsonNode?): EconomicSheet {
        val data = objectOrMissing(raw)
        val revenue = objectOrMissing(data.path("revenue"))
        val seeds = list(data.get("seeds")) { item, index ->
            SeedLine(
                id(item.get("id"), index), str(item.get("supplier"), 120), str(item.get("hybrid"), 120),
                if (item.path("category").isTextual) str(item.get("category"), 40) else null,
                if (item.path("unit").asText(null) == "KG") "KG" else "SEEDS",
                num(item.get("bagSize"), 1.0), num(item.get("ratePerHa"), 0.0), num(item.get("pricePerBag"), 0.0),
                num(item.get("bagsUsed")),
            )
        }
        val fertilizers = list(data.get("fertilizers")) { item, index ->
            FertilizerLine(
                id(item.get("id"), index), str(item.get("product"), 120), num(item.get("ratePerHaKg"), 0.0),
                num(item.get("pricePerT"), 0.0), num(item.get("applications"), 1.0),
                num(item.get("n")), num(item.get("p")), num(item.get("k")),
            )
        }
        val chemicals = list(data.get("chemicals")) { item, index ->
            ChemicalLine(
                id(item.get("id"), index), str(item.get("product"), 120), chemicalKind(item.path("kind").asText(null)),
                num(item.get("dosePerHa"), 0.0), if (item.path("unit").asText(null) == "KG") "KG" else "L",
                num(item.get("pricePerUnit"), 0.0), num(item.get("applications"), 1.0),
            )
        }
        val misc = list(data.get("misc")) { item, index ->
            MiscLine(
                id(item.get("id"), index), str(item.get("label"), 120), num(item.get("amount"), 0.0),
                if (item.path("basis").asText(null) == "PER_HA") "PER_HA" else "TOTAL",
            )
        }
        return EconomicSheet(
            seeds, fertilizers, chemicals, misc,
            RevenueInputs(
                num(revenue.get("expectedYieldTHa")), num(revenue.get("actualYieldTHa")),
                num(revenue.get("pricePerT")), num(revenue.get("actualPricePerT")),
            ),
        )
    }

    private fun chemicalKind(value: String?): ChemicalKind =
        ChemicalKind.entries.firstOrNull { it.name == value } ?: ChemicalKind.HERBICIDE

    /** Reads stored settings, filling in the defaults. */
    @JvmStatic
    fun parseSettings(raw: JsonNode?): EconomicSettings {
        val data = objectOrMissing(raw)
        val labor = LinkedHashMap<String, LaborProfile>()
        forEachObject(data.get("labor")) { key, item ->
            labor[key] = LaborProfile(num(item.get("monthlySalary")), num(item.get("hourlyRate")))
        }
        val machines = LinkedHashMap<String, MachineCostProfile>()
        forEachObject(data.get("machines")) { key, item ->
            machines[key] = MachineCostProfile(
                num(item.get("purchasePrice"), 0.0), num(item.get("residualPct"), 20.0), num(item.get("depreciationYears"), 10.0),
                num(item.get("annualHours"), 500.0), num(item.get("serviceCostPerYear"), 0.0), num(item.get("maintenanceCostPerYear"), 0.0),
            )
        }
        return EconomicSettings(
            num(data.get("fuelPricePerL"), ImplementCatalog.DEFAULT_FUEL_PRICE_EUR),
            num(data.get("defaultLaborRate"), DEFAULT_LABOR_RATE_EUR_H), labor, machines,
        )
    }

    /** Visits the object-valued fields of a JSON object, looking at no more than 500 fields. */
    private fun forEachObject(value: JsonNode?, consumer: (String, JsonNode) -> Unit) {
        if (value == null || !value.isObject) {
            return
        }
        for ((key, item) in value.properties().take(500)) {
            if (item != null && item.isObject) {
                consumer(key, item)
            }
        }
    }
}
