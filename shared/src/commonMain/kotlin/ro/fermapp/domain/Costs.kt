package ro.fermapp.domain

import kotlinx.serialization.Serializable

@Serializable
enum class CostCategory { SEED, FERTILIZER, PESTICIDE, FUEL, LABOR, IRRIGATION, OTHER }

val COST_CATEGORIES: List<CostCategory> = CostCategory.entries

@Serializable
data class CostItem(val category: CostCategory, val amount: Double)

data class CostSummaryInput(
    val areaHa: Double,
    val costs: List<CostItem>,
    val expectedYieldTHa: Double? = null,
    val expectedPricePerT: Double? = null,
    val actualYieldT: Double? = null,
    val actualPricePerT: Double? = null,
)

@Serializable
data class CostSummary(
    val totalCost: Double,
    val costPerHa: Double,
    val byCategory: Map<CostCategory, Double>,
    val estimatedRevenue: Double?,
    val estimatedProfit: Double?,
    val actualRevenue: Double?,
    val actualProfit: Double?,
)

internal fun money(value: Double) = jsRound(value * 100) / 100

fun summarizeCosts(input: CostSummaryInput): CostSummary {
    val sums = COST_CATEGORIES.associateWith { 0.0 }.toMutableMap()
    for (cost in input.costs) sums[cost.category] = sums.getValue(cost.category) + cost.amount
    val byCategory = sums.mapValues { money(it.value) }

    val totalCost = money(input.costs.sumOf { it.amount })
    val estimatedRevenue = if (input.expectedYieldTHa != null && input.expectedPricePerT != null) {
        money(input.expectedYieldTHa * input.areaHa * input.expectedPricePerT)
    } else {
        null
    }
    val actualRevenue = if (input.actualYieldT != null && input.actualPricePerT != null) {
        money(input.actualYieldT * input.actualPricePerT)
    } else {
        null
    }

    return CostSummary(
        totalCost = totalCost,
        costPerHa = if (input.areaHa > 0) money(totalCost / input.areaHa) else 0.0,
        byCategory = byCategory,
        estimatedRevenue = estimatedRevenue,
        estimatedProfit = estimatedRevenue?.let { money(it - totalCost) },
        actualRevenue = actualRevenue,
        actualProfit = actualRevenue?.let { money(it - totalCost) },
    )
}
