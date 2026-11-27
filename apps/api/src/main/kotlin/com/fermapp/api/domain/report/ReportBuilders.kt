package com.fermapp.api.domain.report

import com.fermapp.api.common.util.Js
import com.fermapp.api.domain.crops.Crops
import com.fermapp.api.domain.economics.EconomicModels.EconomicCategory
import com.fermapp.api.domain.economics.EconomicModels.FarmEconomics
import com.fermapp.api.domain.economics.EconomicModels.FarmParcelEconomics
import com.fermapp.api.domain.economics.Economics
import com.fermapp.api.domain.economics.Money
import com.fermapp.api.domain.fieldstatus.FieldStatusRules
import com.fermapp.api.domain.fieldstatus.FieldStatusRules.FieldState
import com.fermapp.api.domain.fieldstatus.FieldStatusRules.FieldStatus
import com.fermapp.api.domain.fieldstatus.FieldStatusRules.FieldTask
import com.fermapp.api.domain.model.CropCode
import com.fermapp.api.domain.report.ReportTable.Chart
import com.fermapp.api.domain.report.ReportTable.Column
import com.fermapp.api.domain.report.ReportTable.Point
import com.fermapp.api.domain.report.ReportTable.SummaryItem
import com.fermapp.api.domain.sumOfDoubles
import com.fermapp.api.domain.tillage.Tillage
import com.fermapp.api.domain.tillage.Tillage.TillageSystem
import java.time.Instant
import java.util.function.Function
import kotlin.math.abs
import kotlin.math.min

/**
 * Report tables shared by the server and the phone, so a report has the same columns,
 * totals, chart and summary whichever backend produced it (reportBuilders.ts).
 *
 * Row values keep the boxed types of the Java version (Integer for counts, Double for
 * measures), because the PDF and Excel writers format them by type.
 */
object ReportBuilders {

    @JvmRecord
    data class ReportParcel(
        val id: String,
        val name: String?,
        val code: String?,
        val areaHa: Double,
        val locality: String?,
        val crop: CropCode?,
        val tillage: TillageSystem?,
    )

    @JvmRecord
    data class ReportRun(
        val parcelId: String?,
        val machineId: String?,
        val type: String?,
        val status: String?,
        val areaHa: Double,
        val percent: Double,
        val hours: Double,
        val fuelL: Double?,
        val startedAt: String,
    )

    @JvmRecord
    data class ReportMachine(val id: String, val name: String?, val type: String?, val operatorName: String?)

    @JvmRecord
    data class ReportSeason(val parcelId: String?, val crop: CropCode, val yieldT: Double?)

    private val MACHINE_TYPES = mapOf(
        "TRACTOR" to "Tractor", "COMBINE" to "Combine", "SPRAYER" to "Sprayer", "SEEDER" to "Seed drill",
        "DISC" to "Disc", "CULTIVATOR" to "Cultivator", "SUBSOILER" to "Subsoiler", "TRAILER" to "Trailer",
    )

    private fun round(value: Double): Double = Js.round(value, 2)

    private fun numberOf(value: Any?): Double = (value as? Number)?.toDouble() ?: 0.0

    private fun isActive(run: ReportRun) = (run.status == "RUNNING" || run.status == "PAUSED") && run.percent < 100

    private fun row(vararg pairs: Pair<String, Any?>): MutableMap<String, Any?> = linkedMapOf(*pairs)

    private fun List<Map<String, Any?>>.total(key: String): Double = sumOfDoubles { numberOf(it[key]) }

    private fun workedArea(run: ReportRun): Double = run.areaHa * min(100.0, run.percent) / 100

    private fun statusOf(parcel: ReportParcel, tasks: List<FieldTask>, runs: List<ReportRun>, now: Instant): FieldStatus =
        FieldStatusRules.fieldStatus(
            tasks.filter { parcel.id == it.parcelId },
            runs.count { it.parcelId == parcel.id && isActive(it) },
            now,
        )

    @JvmStatic
    fun parcelsReport(year: Int, parcels: List<ReportParcel>, tasks: List<FieldTask>, runs: List<ReportRun>, now: Instant): ReportTable {
        val rows = parcels.map { parcel ->
            val status = statusOf(parcel, tasks, runs, now)
            row(
                "parcel" to parcel.name,
                "code" to parcel.code,
                "area" to round(parcel.areaHa),
                "crop" to (parcel.crop?.let(Crops::name) ?: ""),
                "tillage" to (parcel.tillage?.let { Tillage.profile(it).badge } ?: ""),
                "status" to FieldStatusRules.LABELS[status.state],
                "open" to status.pending + status.later,
                "overdue" to status.overdue,
                "completion" to status.completionScore,
            )
        }
        val scored = rows.filter { it["completion"] != null }
        val totalArea = rows.total("area")
        return ReportTable(
            "Parcels", "Year $year",
            listOf(
                Column.text("parcel", "Parcel", 1.4), Column.text("code", "Code", 0.7), Column.number("area", "ha", 0.6),
                Column.text("crop", "Crop", 0.9), Column.text("tillage", "Tillage", 0.7), Column.text("status", "Status", 1.1),
                Column.number("open", "Open tasks", 0.7), Column.number("overdue", "Overdue", 0.7),
                Column.number("completion", "Completion %", 0.8),
            ),
            rows,
            row("area" to round(totalArea), "open" to rows.total("open"), "overdue" to rows.total("overdue")),
            "landscape",
            Chart("Area by parcel", "parcel", "area", null, null, "ha"),
            listOf(
                SummaryItem("Parcels", rows.size.toString()),
                SummaryItem("Total area", Js.num(Js.round(totalArea, 1)) + " ha"),
                SummaryItem(
                    "Average completion",
                    if (scored.isEmpty()) "-" else "${Math.round(scored.total("completion") / scored.size)}%",
                ),
                SummaryItem("With overdue work", rows.count { numberOf(it["overdue"]) > 0 }.toString()),
            ),
        )
    }

    @JvmStatic
    fun machinesReport(year: Int, machines: List<ReportMachine>, runs: List<ReportRun>, parcelName: Function<String?, String>): ReportTable {
        val yearPrefix = year.toString()
        val rows = machines.map { machine ->
            val own = runs.filter { machine.id == it.machineId && it.startedAt.startsWith(yearPrefix) && it.status != "CANCELLED" }
            val active = own.firstOrNull(::isActive)
            row(
                "machine" to machine.name,
                "type" to (MACHINE_TYPES[machine.type] ?: machine.type),
                "operator" to (machine.operatorName ?: ""),
                "operations" to own.size,
                "hours" to Js.round(own.sumOfDoubles { it.hours }, 1),
                "area" to Js.round(own.sumOfDoubles(::workedArea), 1),
                "fuel" to Js.round(own.sumOfDoubles { it.fuelL ?: 0.0 }),
                "status" to if (active != null) "Working, " + parcelName.apply(active.parcelId) else "Available",
            )
        }
        val hours = Js.round(rows.total("hours"), 1)
        val fuel = rows.total("fuel")
        return ReportTable(
            "Machines", "Year $year",
            listOf(
                Column.text("machine", "Machine", 1.4), Column.text("type", "Type", 0.9), Column.text("operator", "Operator", 1.1),
                Column.number("operations", "Operations", 0.8), Column.number("hours", "Hours", 0.7),
                Column.number("area", "Area (ha)", 0.8), Column.number("fuel", "Diesel (l)", 0.8), Column.text("status", "Status", 1.3),
            ),
            rows,
            row(
                "operations" to rows.total("operations"), "hours" to hours,
                "area" to Js.round(rows.total("area"), 1), "fuel" to fuel,
            ),
            "landscape",
            Chart("Working hours by machine", "machine", "hours", null, null, "h"),
            listOf(
                SummaryItem("Machines", rows.size.toString()),
                SummaryItem("Working now", rows.count { it["status"] != "Available" }.toString()),
                SummaryItem("Hours worked", Js.num(Js.round(rows.total("hours"), 0)) + " h"),
                SummaryItem("Diesel", Js.num(fuel) + " l"),
            ),
        )
    }

    private class CropGroup {
        var parcels = 0
        var area = 0.0
        var yieldT = 0.0
        var harvestedArea = 0.0
    }

    @JvmStatic
    fun cropsReport(year: Int, parcels: List<ReportParcel>, seasons: List<ReportSeason>): ReportTable {
        val groups = LinkedHashMap<CropCode, CropGroup>()
        for (season in seasons) {
            val parcel = parcels.firstOrNull { it.id == season.parcelId } ?: continue
            val group = groups.getOrPut(season.crop) { CropGroup() }
            group.parcels += 1
            group.area += parcel.areaHa
            val yieldT = season.yieldT
            if (yieldT != null) {
                group.yieldT += yieldT
                group.harvestedArea += parcel.areaHa
            }
        }
        val total = groups.values.sumOfDoubles { it.area }
        val rows = groups
            .map { (crop, group) ->
                row(
                    "crop" to Crops.name(crop),
                    "parcels" to group.parcels,
                    "area" to round(group.area),
                    "share" to if (total > 0) Js.round(group.area / total * 100, 1) else 0.0,
                    "yield" to if (group.yieldT != 0.0) Js.round(group.yieldT, 1) else null,
                    "perHa" to if (group.harvestedArea != 0.0) round(group.yieldT / group.harvestedArea) else null,
                )
            }
            .sortedByDescending { numberOf(it["area"]) }
            .toMutableList()
        val harvested = rows.total("yield")
        return ReportTable(
            "Crops", "Year $year",
            listOf(
                Column.text("crop", "Crop", 1.3), Column.number("parcels", "Parcels", 0.7), Column.number("area", "Area (ha)", 0.9),
                Column.number("share", "Share (%)", 0.8), Column.number("yield", "Yield (t)", 0.9), Column.number("perHa", "t/ha", 0.7),
            ),
            rows,
            row("parcels" to rows.total("parcels"), "area" to round(total), "yield" to Js.round(harvested, 1)),
            "portrait",
            Chart("Area by crop", "crop", "area", null, null, "ha"),
            listOf(
                SummaryItem("Crops", rows.size.toString()),
                SummaryItem("Cultivated area", Js.num(Js.round(total, 1)) + " ha"),
                SummaryItem("Harvested", Js.num(Js.round(harvested, 0)) + " t"),
            ),
        )
    }

    @JvmStatic
    fun profitabilityReport(year: Int, parcels: List<FarmParcelEconomics>, summary: FarmEconomics): ReportTable {
        val rows = parcels
            .filter { it.result.season.total > 0 }
            .map { item ->
                val result = item.result
                val outlook = result.actual ?: result.expected
                row(
                    "parcel" to item.name,
                    "crop" to (item.cropName ?: ""),
                    "area" to round(result.areaHa),
                    "costPerHa" to result.season.perHa,
                    "revenuePerHa" to outlook?.revenuePerHa,
                    "profitPerHa" to outlook?.profitPerHa,
                    "margin" to outlook?.marginPct,
                    "profit" to outlook?.profit,
                    "basis" to when {
                        result.actual != null -> "Actual"
                        result.expected != null -> "Expected"
                        else -> ""
                    },
                )
            }
        return ReportTable(
            "Profitability", "Year $year, in euro",
            listOf(
                Column.text("parcel", "Parcel", 1.3), Column.text("crop", "Crop", 0.9), Column.number("area", "ha", 0.6),
                Column.money("costPerHa", "Cost/ha", 0.9), Column.money("revenuePerHa", "Revenue/ha", 0.9),
                Column.money("profitPerHa", "Profit/ha", 0.9), Column.number("margin", "Margin %", 0.7),
                Column.money("profit", "Profit", 1.0), Column.text("basis", "Basis", 0.7),
            ),
            rows,
            row("area" to round(rows.total("area")), "profit" to round(rows.total("profit"))),
            "landscape",
            Chart("Profit by parcel", "parcel", "profit", null, true, null),
            listOf(
                SummaryItem("Average cost", Money.formatEuro(summary.costPerHa, 0) + "/ha"),
                SummaryItem("Average profit", summary.profitPerHa?.let { Money.formatEuro(it, 0) + "/ha" } ?: "-"),
                SummaryItem("Best field", summary.mostProfitableParcel?.label ?: "-"),
                SummaryItem("Best crop", summary.mostProfitableCrop?.label ?: "-"),
            ),
        )
    }

    @JvmStatic
    fun farmReport(
        year: Int,
        farmName: String?,
        parcels: List<ReportParcel>,
        runs: List<ReportRun>,
        tasks: List<FieldTask>,
        economics: FarmEconomics?,
        now: Instant,
    ): ReportTable {
        val yearPrefix = year.toString()
        val yearRuns = runs.filter { it.startedAt.startsWith(yearPrefix) && it.status != "CANCELLED" }
        val area = parcels.sumOfDoubles { it.areaHa }
        val cultivated = parcels.filter { it.crop != null }.sumOfDoubles { it.areaHa }
        val statuses = parcels.map { statusOf(it, tasks, runs, now) }
        val scored = statuses.filter { it.completionScore != null }
        val byType = LinkedHashMap<String?, Double>()
        for (run in yearRuns) {
            byType.merge(run.type, workedArea(run)) { a, b -> a + b }
        }
        val completion = if (scored.isEmpty()) {
            "-"
        } else {
            "${Math.round(scored.sumOfDoubles { it.completionScore!!.toDouble() } / scored.size)}%"
        }

        val rows = mutableListOf<Map<String, Any?>>()
        fun metric(label: String?, value: String) {
            rows += row("metric" to label, "value" to value)
        }
        metric("Parcels", parcels.size.toString())
        metric("Total area", Js.num(Js.round(area, 1)) + " ha")
        metric("Cultivated area", Js.num(Js.round(cultivated, 1)) + " ha")
        metric("Operations carried out", yearRuns.size.toString())
        metric("Area worked", Js.num(Js.round(yearRuns.sumOfDoubles(::workedArea), 1)) + " ha")
        metric("Diesel used", "${Math.round(yearRuns.sumOfDoubles { it.fuelL ?: 0.0 })} l")
        metric("Parcels fully completed", statuses.count { it.state == FieldState.COMPLETE }.toString())
        metric("Parcels with overdue work", statuses.count { it.state == FieldState.OVERDUE }.toString())
        metric("Average field completion", completion)
        for ((type, worked) in byType) {
            metric(Economics.WORK_OPERATION_LABELS[type] ?: type, Js.num(Js.round(worked, 1)) + " ha")
        }
        val categoryRows = mutableListOf<EconomicCategory>()
        if (economics != null) {
            metric("Production cost", Money.formatEuro(economics.cost, 0))
            metric("Cost per hectare", Money.formatEuro(economics.costPerHa, 2))
            metric("Profit", Money.formatEuro(economics.actualProfit ?: economics.expectedProfit, 0))
            for (category in EconomicCategory.entries) {
                val amount = economics.byCategory.getValue(category)
                if (amount > 0) {
                    categoryRows += category
                    metric("Cost: " + Economics.LABELS[category], Money.formatEuro(amount, 0))
                }
            }
        }
        val chart = if (economics != null && categoryRows.isNotEmpty()) {
            Chart(
                "Production cost by category", null, null,
                categoryRows.map { Point(Economics.LABELS.getValue(it), economics.byCategory.getValue(it)) },
                true, null,
            )
        } else {
            null
        }
        val summary = mutableListOf(
            SummaryItem("Area", Js.num(Js.round(area, 1)) + " ha"),
            SummaryItem("Operations", yearRuns.size.toString()),
        )
        if (economics != null) {
            summary += SummaryItem("Cost per hectare", Money.formatEuro(economics.costPerHa, 0))
        }
        summary += SummaryItem("Field completion", completion)
        return ReportTable(
            "Farm overview, $farmName", "Year $year",
            listOf(Column.text("metric", "Indicator", 2.0), Column.number("value", "Value", 1.2)),
            rows, null, "portrait", chart, summary,
        )
    }

    /** The chart points of a report, largest first, capped for legibility. */
    @JvmStatic
    fun reportChartPoints(report: ReportTable, max: Int): List<Point> {
        val chart = report.chart ?: return listOf()
        val labelKey = chart.labelKey
        val valueKey = chart.valueKey
        val points = when {
            chart.points != null -> chart.points
            labelKey != null && valueKey != null -> report.rows.map { Point(it[labelKey]?.toString() ?: "", toNumber(it[valueKey])) }
            else -> listOf()
        }
        return points
            .filter { it.value.isFinite() && it.value != 0.0 }
            .sortedByDescending { abs(it.value) }
            .take(max)
    }

    /** `Number(value)`: null is 0, text is parsed, anything else is NaN. */
    private fun toNumber(value: Any?): Double {
        if (value == null) {
            return 0.0
        }
        if (value is Number) {
            return value.toDouble()
        }
        // String.trim() semantics: strip control characters and spaces, not every Unicode space.
        val text = value.toString().trim { it <= ' ' }
        if (text.isEmpty()) {
            return 0.0
        }
        return try {
            java.lang.Double.parseDouble(text)
        } catch (e: NumberFormatException) {
            Double.NaN
        }
    }
}
