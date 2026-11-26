package ro.fermapp.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.time.Clock
import kotlin.time.Instant

/*
 * Report tables shared by the server and the phone, so a report has the same
 * columns, totals, chart and summary whichever backend produced it.
 */

@Serializable
data class ReportColumn(
    val key: String,
    val label: String,
    /** Relative width in PDF tables. */
    val width: Double,
    val numeric: Boolean = false,
    /** An amount in euro. */
    val money: Boolean = false,
)

@Serializable
data class ChartPoint(val label: String, val value: Double)

@Serializable
data class ReportChart(
    val title: String,
    /** Columns the bars read from each row... */
    val labelKey: String? = null,
    val valueKey: String? = null,
    /** ...or points given directly, when the bars are not rows of the table. */
    val points: List<ChartPoint>? = null,
    val money: Boolean = false,
    val unit: String? = null,
)

@Serializable
enum class Orientation { portrait, landscape }

@Serializable
data class SummaryItem(val label: String, val value: String)

/** A table cell: text, a number or empty, as JSON primitives. */
typealias ReportRow = Map<String, JsonElement>

@Serializable
data class ReportTable(
    val title: String,
    val period: String,
    val columns: List<ReportColumn>,
    val rows: List<ReportRow>,
    val totals: Map<String, JsonElement>? = null,
    /** Wide tables print in landscape; the user may still choose. */
    val orientation: Orientation? = null,
    val chart: ReportChart? = null,
    /** Headline figures printed above the table. */
    val summary: List<SummaryItem>? = null,
)

@Serializable
data class ReportParcel(
    val id: String,
    val name: String,
    val code: String,
    val areaHa: Double,
    val locality: String?,
    val crop: CropCode?,
    val tillage: TillageSystem?,
)

@Serializable
data class ReportRun(
    val parcelId: String,
    val machineId: String?,
    val type: String,
    val status: OperationStatus,
    val areaHa: Double,
    val percent: Double,
    val hours: Double,
    val fuelL: Double?,
    val startedAt: String,
)

@Serializable
data class ReportMachine(val id: String, val name: String, val type: String, val operatorName: String?)

@Serializable
data class ReportSeason(val parcelId: String, val crop: CropCode, val yieldT: Double?)

/** A task with the parcel it belongs to. */
data class ParcelTask(val task: FieldTask, val parcelId: String?)

private fun round(value: Double, digits: Int = 2) = jsRound(value * 10.0.pow(digits)) / 10.0.pow(digits)

private fun cell(value: String) = JsonPrimitive(value)
private fun cell(value: Double?) = if (value == null) JsonNull else JsonPrimitive(jsonNumber(value))
private fun cell(value: Int?) = if (value == null) JsonNull else JsonPrimitive(value)

/** Whole numbers are written without decimals, as JavaScript does. */
private fun jsonNumber(value: Double): Number = if (value == kotlin.math.floor(value) && abs(value) < 1e15) value.toLong() else value

private fun number(element: JsonElement?): Double? = (element as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

private val MACHINE_TYPES = mapOf(
    "TRACTOR" to "Tractor", "COMBINE" to "Combine", "SPRAYER" to "Sprayer", "SEEDER" to "Seed drill",
    "DISC" to "Disc", "CULTIVATOR" to "Cultivator", "SUBSOILER" to "Subsoiler", "TRAILER" to "Trailer",
)

private fun isActive(run: ReportRun) = (run.status == OperationStatus.RUNNING || run.status == OperationStatus.PAUSED) && run.percent < 100

private fun worked(run: ReportRun) = (run.areaHa * min(100.0, run.percent)) / 100

fun parcelsReport(year: Int, parcels: List<ReportParcel>, tasks: List<ParcelTask>, runs: List<ReportRun>, now: Instant = Clock.System.now()): ReportTable {
    data class Row(val parcel: String, val code: String, val area: Double, val crop: String, val tillage: String, val status: String, val open: Int, val overdue: Int, val completion: Int?)

    val rows = parcels.map { parcel ->
        val status = fieldStatus(
            tasks = tasks.filter { it.parcelId == parcel.id }.map { it.task },
            activeWork = runs.count { it.parcelId == parcel.id && isActive(it) },
            now = now,
        )
        Row(
            parcel.name, parcel.code, round(parcel.areaHa), parcel.crop?.profile?.name ?: "", parcel.tillage?.profile?.badge ?: "",
            status.state.label, status.pending + status.later, status.overdue, status.completionScore,
        )
    }
    val scored = rows.filter { it.completion != null }
    val totalArea = rows.sumOf { it.area }
    return ReportTable(
        title = "Parcels",
        period = "Year $year",
        orientation = Orientation.landscape,
        columns = listOf(
            ReportColumn("parcel", "Parcel", 1.4),
            ReportColumn("code", "Code", 0.7),
            ReportColumn("area", "ha", 0.6, numeric = true),
            ReportColumn("crop", "Crop", 0.9),
            ReportColumn("tillage", "Tillage", 0.7),
            ReportColumn("status", "Status", 1.1),
            ReportColumn("open", "Open tasks", 0.7, numeric = true),
            ReportColumn("overdue", "Overdue", 0.7, numeric = true),
            ReportColumn("completion", "Completion %", 0.8, numeric = true),
        ),
        rows = rows.map {
            mapOf(
                "parcel" to cell(it.parcel), "code" to cell(it.code), "area" to cell(it.area), "crop" to cell(it.crop),
                "tillage" to cell(it.tillage), "status" to cell(it.status), "open" to cell(it.open), "overdue" to cell(it.overdue),
                "completion" to cell(it.completion),
            )
        },
        totals = mapOf("area" to cell(round(totalArea)), "open" to cell(rows.sumOf { it.open }), "overdue" to cell(rows.sumOf { it.overdue })),
        chart = ReportChart("Area by parcel", labelKey = "parcel", valueKey = "area", unit = "ha"),
        summary = listOf(
            SummaryItem("Parcels", rows.size.toString()),
            SummaryItem("Total area", "${jsNum(round(totalArea, 1))} ha"),
            SummaryItem("Average completion", if (scored.isNotEmpty()) "${jsRoundInt(scored.sumOf { it.completion!! }.toDouble() / scored.size)}%" else "-"),
            SummaryItem("With overdue work", rows.count { it.overdue > 0 }.toString()),
        ),
    )
}

fun machinesReport(year: Int, machines: List<ReportMachine>, runs: List<ReportRun>, parcelName: (String) -> String): ReportTable {
    data class Row(val machine: String, val type: String, val operator: String, val operations: Int, val hours: Double, val area: Double, val fuel: Double, val status: String)

    val rows = machines.map { machine ->
        val machineRuns = runs.filter { it.machineId == machine.id && it.startedAt.startsWith(year.toString()) && it.status != OperationStatus.CANCELLED }
        val active = machineRuns.firstOrNull(::isActive)
        Row(
            machine = machine.name,
            type = MACHINE_TYPES[machine.type] ?: machine.type,
            operator = machine.operatorName ?: "",
            operations = machineRuns.size,
            hours = round(machineRuns.sumOf { it.hours }, 1),
            area = round(machineRuns.sumOf(::worked), 1),
            fuel = jsRound(machineRuns.sumOf { it.fuelL ?: 0.0 }),
            status = if (active != null) "Working, ${parcelName(active.parcelId)}" else "Available",
        )
    }
    val fuel = rows.sumOf { it.fuel }
    return ReportTable(
        title = "Machines",
        period = "Year $year",
        orientation = Orientation.landscape,
        columns = listOf(
            ReportColumn("machine", "Machine", 1.4),
            ReportColumn("type", "Type", 0.9),
            ReportColumn("operator", "Operator", 1.1),
            ReportColumn("operations", "Operations", 0.8, numeric = true),
            ReportColumn("hours", "Hours", 0.7, numeric = true),
            ReportColumn("area", "Area (ha)", 0.8, numeric = true),
            ReportColumn("fuel", "Diesel (l)", 0.8, numeric = true),
            ReportColumn("status", "Status", 1.3),
        ),
        rows = rows.map {
            mapOf(
                "machine" to cell(it.machine), "type" to cell(it.type), "operator" to cell(it.operator), "operations" to cell(it.operations),
                "hours" to cell(it.hours), "area" to cell(it.area), "fuel" to cell(it.fuel), "status" to cell(it.status),
            )
        },
        totals = mapOf(
            "operations" to cell(rows.sumOf { it.operations }),
            "hours" to cell(round(rows.sumOf { it.hours }, 1)),
            "area" to cell(round(rows.sumOf { it.area }, 1)),
            "fuel" to cell(fuel),
        ),
        chart = ReportChart("Working hours by machine", labelKey = "machine", valueKey = "hours", unit = "h"),
        summary = listOf(
            SummaryItem("Machines", rows.size.toString()),
            SummaryItem("Working now", rows.count { it.status != "Available" }.toString()),
            SummaryItem("Hours worked", "${jsNum(round(rows.sumOf { it.hours }, 0))} h"),
            SummaryItem("Diesel", "${jsNum(fuel)} l"),
        ),
    )
}

fun cropsReport(year: Int, parcels: List<ReportParcel>, seasons: List<ReportSeason>): ReportTable {
    class Group(var parcels: Int = 0, var area: Double = 0.0, var yieldT: Double = 0.0, var harvestedArea: Double = 0.0)
    data class Row(val crop: String, val parcels: Int, val area: Double, val share: Double, val yield: Double?, val perHa: Double?)

    val groups = linkedMapOf<CropCode, Group>()
    for (season in seasons) {
        val parcel = parcels.firstOrNull { it.id == season.parcelId } ?: continue
        val group = groups.getOrPut(season.crop) { Group() }
        group.parcels++
        group.area += parcel.areaHa
        if (season.yieldT != null) {
            group.yieldT += season.yieldT
            group.harvestedArea += parcel.areaHa
        }
    }
    val total = groups.values.sumOf { it.area }
    val rows = groups.map { (crop, group) ->
        Row(
            crop = crop.profile.name,
            parcels = group.parcels,
            area = round(group.area),
            share = if (total > 0) round((group.area / total) * 100, 1) else 0.0,
            yield = if (group.yieldT != 0.0) round(group.yieldT, 1) else null,
            perHa = if (group.harvestedArea != 0.0) round(group.yieldT / group.harvestedArea) else null,
        )
    }.sortedByDescending { it.area }
    val harvested = rows.sumOf { it.yield ?: 0.0 }
    return ReportTable(
        title = "Crops",
        period = "Year $year",
        orientation = Orientation.portrait,
        columns = listOf(
            ReportColumn("crop", "Crop", 1.3),
            ReportColumn("parcels", "Parcels", 0.7, numeric = true),
            ReportColumn("area", "Area (ha)", 0.9, numeric = true),
            ReportColumn("share", "Share (%)", 0.8, numeric = true),
            ReportColumn("yield", "Yield (t)", 0.9, numeric = true),
            ReportColumn("perHa", "t/ha", 0.7, numeric = true),
        ),
        rows = rows.map {
            mapOf(
                "crop" to cell(it.crop), "parcels" to cell(it.parcels), "area" to cell(it.area),
                "share" to cell(it.share), "yield" to cell(it.yield), "perHa" to cell(it.perHa),
            )
        },
        totals = mapOf("parcels" to cell(rows.sumOf { it.parcels }), "area" to cell(round(total)), "yield" to cell(round(harvested, 1))),
        chart = ReportChart("Area by crop", labelKey = "crop", valueKey = "area", unit = "ha"),
        summary = listOf(
            SummaryItem("Crops", rows.size.toString()),
            SummaryItem("Cultivated area", "${jsNum(round(total, 1))} ha"),
            SummaryItem("Harvested", "${jsNum(round(harvested, 0))} t"),
        ),
    )
}

fun profitabilityReport(year: Int, parcels: List<FarmParcelEconomics>, summary: FarmEconomics): ReportTable {
    data class Row(
        val parcel: String, val crop: String, val area: Double, val costPerHa: Double, val revenuePerHa: Double?,
        val profitPerHa: Double?, val margin: Double?, val profit: Double?, val basis: String,
    )

    val rows = parcels.filter { it.result.season.total > 0 }.map { item ->
        val outlook = item.result.actual ?: item.result.expected
        Row(
            parcel = item.name,
            crop = item.cropName ?: "",
            area = round(item.result.areaHa),
            costPerHa = item.result.season.perHa,
            revenuePerHa = outlook?.revenuePerHa,
            profitPerHa = outlook?.profitPerHa,
            margin = outlook?.marginPct,
            profit = outlook?.profit,
            basis = when {
                item.result.actual != null -> "Actual"
                item.result.expected != null -> "Expected"
                else -> ""
            },
        )
    }
    return ReportTable(
        title = "Profitability",
        period = "Year $year, in euro",
        orientation = Orientation.landscape,
        columns = listOf(
            ReportColumn("parcel", "Parcel", 1.3),
            ReportColumn("crop", "Crop", 0.9),
            ReportColumn("area", "ha", 0.6, numeric = true),
            ReportColumn("costPerHa", "Cost/ha", 0.9, numeric = true, money = true),
            ReportColumn("revenuePerHa", "Revenue/ha", 0.9, numeric = true, money = true),
            ReportColumn("profitPerHa", "Profit/ha", 0.9, numeric = true, money = true),
            ReportColumn("margin", "Margin %", 0.7, numeric = true),
            ReportColumn("profit", "Profit", 1.0, numeric = true, money = true),
            ReportColumn("basis", "Basis", 0.7),
        ),
        rows = rows.map {
            mapOf(
                "parcel" to cell(it.parcel), "crop" to cell(it.crop), "area" to cell(it.area), "costPerHa" to cell(it.costPerHa),
                "revenuePerHa" to cell(it.revenuePerHa), "profitPerHa" to cell(it.profitPerHa), "margin" to cell(it.margin),
                "profit" to cell(it.profit), "basis" to cell(it.basis),
            )
        },
        totals = mapOf("area" to cell(round(rows.sumOf { it.area })), "profit" to cell(round(rows.sumOf { it.profit ?: 0.0 }))),
        chart = ReportChart("Profit by parcel", labelKey = "parcel", valueKey = "profit", money = true),
        summary = listOf(
            SummaryItem("Average cost", "${formatEuro(summary.costPerHa, 0)}/ha"),
            SummaryItem("Average profit", summary.profitPerHa?.let { "${formatEuro(it, 0)}/ha" } ?: "-"),
            SummaryItem("Best field", summary.mostProfitableParcel?.label ?: "-"),
            SummaryItem("Best crop", summary.mostProfitableCrop?.label ?: "-"),
        ),
    )
}

fun farmReport(
    year: Int,
    farmName: String,
    parcels: List<ReportParcel>,
    runs: List<ReportRun>,
    tasks: List<ParcelTask>,
    economics: FarmEconomics?,
    now: Instant = Clock.System.now(),
): ReportTable {
    val yearRuns = runs.filter { it.startedAt.startsWith(year.toString()) && it.status != OperationStatus.CANCELLED }
    val area = parcels.sumOf { it.areaHa }
    val cultivated = parcels.filter { it.crop != null }.sumOf { it.areaHa }
    val statuses = parcels.map { parcel ->
        fieldStatus(tasks.filter { it.parcelId == parcel.id }.map { it.task }, runs.count { it.parcelId == parcel.id && isActive(it) }, now)
    }
    val scored = statuses.filter { it.completionScore != null }
    val completion = if (scored.isNotEmpty()) "${jsRoundInt(scored.sumOf { it.completionScore!! }.toDouble() / scored.size)}%" else "-"
    val byType = linkedMapOf<String, Double>()
    for (run in yearRuns) byType[run.type] = (byType[run.type] ?: 0.0) + worked(run)

    fun metric(label: String, value: String): ReportRow = mapOf("metric" to cell(label), "value" to cell(value))
    val categoryRows = economics?.let { e -> ECONOMIC_CATEGORIES.filter { e.byCategory.getValue(it) > 0 } } ?: emptyList()
    val rows = buildList {
        add(metric("Parcels", parcels.size.toString()))
        add(metric("Total area", "${jsNum(round(area, 1))} ha"))
        add(metric("Cultivated area", "${jsNum(round(cultivated, 1))} ha"))
        add(metric("Operations carried out", yearRuns.size.toString()))
        add(metric("Area worked", "${jsNum(round(yearRuns.sumOf(::worked), 1))} ha"))
        add(metric("Diesel used", "${jsNum(jsRound(yearRuns.sumOf { it.fuelL ?: 0.0 }))} l"))
        add(metric("Parcels fully completed", statuses.count { it.state == FieldState.COMPLETE }.toString()))
        add(metric("Parcels with overdue work", statuses.count { it.state == FieldState.OVERDUE }.toString()))
        add(metric("Average field completion", completion))
        byType.forEach { (type, done) -> add(metric(WORK_OPERATION_LABELS[type] ?: type, "${jsNum(round(done, 1))} ha")) }
        if (economics != null) {
            add(metric("Production cost", formatEuro(economics.cost, 0)))
            add(metric("Cost per hectare", formatEuro(economics.costPerHa, 2)))
            add(metric("Profit", formatEuro(economics.actualProfit ?: economics.expectedProfit, 0)))
            categoryRows.forEach { add(metric("Cost: ${it.label}", formatEuro(economics.byCategory.getValue(it), 0))) }
        }
    }
    return ReportTable(
        title = "Farm overview, $farmName",
        period = "Year $year",
        orientation = Orientation.portrait,
        columns = listOf(ReportColumn("metric", "Indicator", 2.0), ReportColumn("value", "Value", 1.2, numeric = true)),
        rows = rows,
        chart = if (economics != null && categoryRows.isNotEmpty()) {
            ReportChart(
                "Production cost by category",
                money = true,
                points = categoryRows.map { ChartPoint(it.label, economics.byCategory.getValue(it)) },
            )
        } else {
            null
        },
        summary = buildList {
            add(SummaryItem("Area", "${jsNum(round(area, 1))} ha"))
            add(SummaryItem("Operations", yearRuns.size.toString()))
            if (economics != null) add(SummaryItem("Cost per hectare", formatEuro(economics.costPerHa, 0)))
            add(SummaryItem("Field completion", completion))
        },
    )
}

/** The chart points of a report, largest first, capped for legibility. */
fun reportChartPoints(report: ReportTable, max: Int = 12): List<ChartPoint> {
    val chart = report.chart ?: return emptyList()
    val labelKey = chart.labelKey
    val valueKey = chart.valueKey
    val points = chart.points ?: if (labelKey != null && valueKey != null) {
        report.rows.map { row ->
            val label = (row[labelKey] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: ""
            ChartPoint(label, number(row[valueKey]) ?: Double.NaN)
        }
    } else {
        emptyList()
    }
    return points.filter { it.value.isFinite() && it.value != 0.0 }.sortedByDescending { abs(it.value) }.take(max)
}
