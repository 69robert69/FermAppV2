package com.fermapp.api.modules.reports

import com.fermapp.api.common.error.Errors
import com.fermapp.api.common.util.Js
import com.fermapp.api.common.util.Times
import com.fermapp.api.domain.crops.Crops
import com.fermapp.api.domain.economics.EconomicModels.EconomicCategory
import com.fermapp.api.domain.economics.Economics
import com.fermapp.api.domain.fieldstatus.FieldStatusRules.FieldTask
import com.fermapp.api.domain.model.RunStatus
import com.fermapp.api.domain.report.ReportBuilders
import com.fermapp.api.domain.report.ReportBuilders.ReportMachine
import com.fermapp.api.domain.report.ReportBuilders.ReportParcel
import com.fermapp.api.domain.report.ReportBuilders.ReportRun
import com.fermapp.api.domain.report.ReportBuilders.ReportSeason
import com.fermapp.api.domain.report.ReportTable
import com.fermapp.api.domain.report.ReportTable.Column
import com.fermapp.api.domain.rotation.Rotation
import com.fermapp.api.domain.rotation.Rotation.CropHistoryEntry
import com.fermapp.api.domain.sumOfDoubles
import com.fermapp.api.domain.tillage.Tillage
import com.fermapp.api.modules.economics.EconomicsService
import com.fermapp.api.modules.work.RunProgress
import com.fermapp.api.modules.work.WorkViews
import com.fermapp.api.persistence.entity.CropSeason
import com.fermapp.api.persistence.repository.CropSeasonRepository
import com.fermapp.api.persistence.repository.FarmRepository
import com.fermapp.api.persistence.repository.IrrigationRunRepository
import com.fermapp.api.persistence.repository.MachineRepository
import com.fermapp.api.persistence.repository.ParcelRepository
import com.fermapp.api.persistence.repository.TaskRepository
import com.fermapp.api.persistence.repository.WorkSessionRepository
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** The eleven farm reports (reports.service.ts). Reports with money in them are hidden from operators. */
@Service
class ReportService(
    private val parcels: ParcelRepository,
    private val seasons: CropSeasonRepository,
    private val works: WorkSessionRepository,
    private val runs: IrrigationRunRepository,
    private val tasks: TaskRepository,
    private val machines: MachineRepository,
    private val farms: FarmRepository,
    private val economics: EconomicsService,
) {

    /** The period of a report: from/to for the logs, year for the seasons. */
    @JvmRecord
    data class Range(val from: Instant, val to: Instant, val year: Int)

    private class Sources(
        val parcels: List<ReportParcel>,
        val tasks: List<FieldTask>,
        val runs: List<ReportRun>,
        val machines: List<ReportMachine>,
        val seasons: List<ReportSeason>,
    )

    private fun workReport(farmId: UUID, range: Range): ReportTable {
        val rows = works.findByFarmIdAndStartedAtBetweenOrderByStartedAtAsc(farmId, range.from, range.to).map { item ->
            row(
                "date", day(item.startedAt),
                "parcel", item.parcel.name,
                "work", WorkViews.workLabel(item.type),
                "machine", item.machine?.name ?: "",
                "operator", item.operator.fullName,
                "area", round(item.areaHa * item.progressPct / 100),
                "status", statusLabel(item.status),
            )
        }
        return ReportTable(
            "Completed work", periodOf(range),
            listOf(
                Column.text("date", "Date", 1.1), Column.text("parcel", "Parcel", 1.5), Column.text("work", "Operation", 1.1),
                Column.text("machine", "Machine", 1.4), Column.text("operator", "Operator", 1.4),
                Column.number("area", "Done (ha)", 1.0), Column.text("status", "Status", 1.1),
            ),
            rows, row("area", sum(rows, "area")), null, null, null,
        )
    }

    private fun fuelReport(farmId: UUID, range: Range): ReportTable {
        val byMachine = LinkedHashMap<String, DoubleArray>()
        for (item in works.findByFarmIdAndStatusAndCompletedAtBetween(farmId, RunStatus.COMPLETED, range.from, range.to)) {
            val name = item.machine?.name ?: "No machine"
            val totals = byMachine.getOrPut(name) { DoubleArray(3) }
            totals[0] += 1.0
            totals[1] += item.areaHa * item.progressPct / 100
            totals[2] += item.fuelLiters ?: 0.0
        }
        val rows = byMachine.map { (machine, totals) ->
            row(
                "machine", machine,
                "sessions", totals[0],
                "area", round(totals[1]),
                "fuel", round(totals[2]),
                "perHa", if (totals[1] > 0) round(totals[2] / totals[1]) else 0.0,
            )
        }
        return ReportTable(
            "Diesel consumption", periodOf(range),
            listOf(
                Column.text("machine", "Machine", 2.0), Column.number("sessions", "Operations", 1.0),
                Column.number("area", "Area (ha)", 1.0), Column.number("fuel", "Diesel (l)", 1.0), Column.number("perHa", "l/ha", 1.0),
            ),
            rows, row("sessions", sum(rows, "sessions"), "area", sum(rows, "area"), "fuel", sum(rows, "fuel")), null, null, null,
        )
    }

    /** Real cost per hectare and profit of every parcel: inputs, operations, machinery, labour and recorded expenses. */
    private fun costReport(farmId: UUID, range: Range): ReportTable {
        val result = economics.farmEconomics(farmId, range.year, null)
        val rows = mutableListOf<Map<String, Any?>>()
        for (item in result.parcels) {
            if (!(item.result.season.total > 0)) {
                continue
            }
            val values = row("parcel", item.name, "crop", item.cropName ?: "", "area", Math.round(item.result.areaHa * 100) / 100.0)
            for (category in EconomicCategory.entries) {
                values[category.name] = item.result.season.byCategory[category]
            }
            values["total"] = item.result.season.total
            values["perHa"] = item.result.season.perHa
            values["estimated"] = item.result.expected?.profit
            values["actual"] = item.result.actual?.profit
            rows += values
        }
        val columns = mutableListOf(Column.text("parcel", "Parcel", 1.4), Column.text("crop", "Crop", 0.9), Column.number("area", "ha", 0.6))
        for (category in EconomicCategory.entries) {
            columns += Column.money(category.name, Economics.LABELS.getValue(category), 0.9)
        }
        columns += Column.money("total", "Total", 1.0)
        columns += Column.money("perHa", "Cost/ha", 0.9)
        columns += Column.money("estimated", "Estimated profit", 1.0)
        columns += Column.money("actual", "Actual profit", 1.0)
        val summary = result.summary
        val totals = row("area", summary.areaHa)
        for (category in EconomicCategory.entries) {
            totals[category.name] = summary.byCategory[category]
        }
        totals["total"] = summary.cost
        totals["perHa"] = summary.costPerHa
        summary.expectedProfit?.let { totals["estimated"] = it }
        summary.actualProfit?.let { totals["actual"] = it }
        return ReportTable("Costs and profit", "Year " + range.year + ", in euro", columns, rows, totals, null, null, null)
    }

    private fun yieldReport(farmId: UUID, range: Range): ReportTable {
        val rows = seasons.findForYearOrderByParcelName(farmId, range.year).map { season ->
            row(
                "parcel", season.parcel.name,
                "crop", Crops.name(season.crop),
                "area", season.parcel.areaHa,
                "harvest", day(season.harvestDate),
                "yield", season.yieldT,
                "perHa", season.yieldT?.let { round(it / season.parcel.areaHa) },
                "moisture", season.harvestMoisture,
            )
        }
        return ReportTable(
            "Yields", "Year " + range.year,
            listOf(
                Column.text("parcel", "Parcel", 1.6), Column.text("crop", "Crop", 1.3), Column.number("area", "Area (ha)", 1.0),
                Column.text("harvest", "Harvest", 1.1), Column.number("yield", "Yield (t)", 1.0), Column.number("perHa", "t/ha", 0.8),
                Column.number("moisture", "Moisture (%)", 1.0),
            ),
            rows, row("area", sum(rows, "area"), "yield", sum(rows, "yield")), null, null, null,
        )
    }

    private fun rotationReport(farmId: UUID, range: Range): ReportTable {
        val rows = mutableListOf<Map<String, Any?>>()
        for (parcel in parcels.findByFarmIdAndArchivedAtIsNullOrderByNameAsc(farmId)) {
            val list = seasons.findTop10ByParcelIdOrderByYearDesc(parcel.id)
            val current = list.firstOrNull { it.year == range.year } ?: continue
            val history = list.filter { it.year < range.year }
            val result = Rotation.evaluateRotation(current.crop, range.year, history.map { CropHistoryEntry(it.crop, it.year) })
            rows += row(
                "parcel", parcel.name,
                "crop", Crops.name(current.crop),
                "previous", if (history.isEmpty()) "" else Crops.name(history[0].crop),
                "score", result.score,
                "verdict", result.label.replaceFirst(Regex("^\\S+\\s"), ""),
                "explanation", result.explanation,
            )
        }
        return ReportTable(
            "Crop rotation", "Year " + range.year,
            listOf(
                Column.text("parcel", "Parcel", 1.2), Column.text("crop", "Crop", 1.0), Column.text("previous", "Previous crop", 1.0),
                Column.number("score", "Score", 0.5), Column.text("verdict", "Verdict", 0.9),
                Column.text("explanation", "Explanation", 3.0),
            ),
            rows, null, null, null, null,
        )
    }

    private fun irrigationReport(farmId: UUID, range: Range): ReportTable {
        val rows = runs.findByFarmIdAndStartedAtBetweenOrderByStartedAtAsc(farmId, range.from, range.to).map { run ->
            row(
                "date", day(run.startedAt),
                "parcel", run.parcel.name,
                "system", WorkViews.systemLabel(run.systemType),
                "route", run.routeLengthM,
                "hours", round(run.durationHours * run.progressPct / 100),
                "dose", run.doseMm,
                "percent", run.progressPct,
                "status", statusLabel(run.status),
            )
        }
        return ReportTable(
            "Irrigation", periodOf(range),
            listOf(
                Column.text("date", "Date", 1.1), Column.text("parcel", "Parcel", 1.5), Column.text("system", "System", 0.9),
                Column.number("route", "Route (m)", 0.9), Column.number("hours", "Hours", 0.7), Column.number("dose", "Dose (mm)", 0.8),
                Column.number("percent", "Done (%)", 0.9), Column.text("status", "Status", 1.1),
            ),
            rows, row("hours", sum(rows, "hours")), null, null, null,
        )
    }

    /** Parcels, tasks, runs and machines of the farm in the shape the shared reports read. */
    private fun sources(farmId: UUID, range: Range): Sources {
        val parcelList = parcels.findByFarmIdAndArchivedAtIsNullOrderByNameAsc(farmId)
        val ids = parcelList.map { it.id }
        val yearSeasons: List<CropSeason> = if (ids.isEmpty()) listOf() else seasons.findByParcelIdInAndYear(ids, range.year)
        // The first row wins when a parcel appears twice, as Collectors.toMap with (a, b) -> a did.
        val seasonByParcel = HashMap<UUID, CropSeason>()
        yearSeasons.forEach { seasonByParcel.putIfAbsent(it.parcelId, it) }
        val now = Times.now()
        return Sources(
            parcelList.map { parcel ->
                ReportParcel(
                    parcel.id.toString(), parcel.name, parcel.code, parcel.areaHa, parcel.locality,
                    seasonByParcel[parcel.id]?.crop, Tillage.parse(parcel.tillageSystem),
                )
            },
            tasks.findByFarmId(farmId).map { task ->
                FieldTask(
                    task.id.toString(), task.title, task.type.name, task.status.name, Times.iso(task.dueDate)!!,
                    task.parcelId?.toString(),
                )
            },
            works.findByFarmId(farmId).map { work ->
                val percent = RunProgress.of(work, now).percent
                val hours = work.durationHours * percent / 100
                val consumption = work.machine?.avgConsumptionLh
                val fuel = work.fuelLiters ?: if (consumption != null && consumption != 0.0) consumption * hours else null
                ReportRun(
                    work.parcelId.toString(), work.machineId?.toString(),
                    work.type.name, work.status.name, work.areaHa, percent, hours, fuel,
                    Times.iso(work.startedAt)!!,
                )
            },
            machines.findActiveWithOperator(farmId).map { machine ->
                ReportMachine(machine.id.toString(), machine.name, machine.type.name, machine.operator?.fullName)
            },
            yearSeasons.map { ReportSeason(it.parcelId.toString(), it.crop, it.yieldT) },
        )
    }

    @Transactional(readOnly = true)
    fun build(type: String, farmId: UUID, range: Range): ReportTable = when (type) {
        "parcels" -> {
            val data = sources(farmId, range)
            ReportBuilders.parcelsReport(range.year, data.parcels, data.tasks, data.runs, Times.now())
        }
        "machines" -> {
            val data = sources(farmId, range)
            ReportBuilders.machinesReport(range.year, data.machines, data.runs) { id ->
                data.parcels.firstOrNull { it.id == id }?.name ?: ""
            }
        }
        "crops" -> {
            val data = sources(farmId, range)
            ReportBuilders.cropsReport(range.year, data.parcels, data.seasons)
        }
        "profitability" -> {
            val result = economics.farmEconomics(farmId, range.year, null)
            ReportBuilders.profitabilityReport(range.year, result.parcels, result.summary)
        }
        "farm" -> {
            val data = sources(farmId, range)
            val result = economics.farmEconomics(farmId, range.year, null)
            val farmName = farms.findById(farmId).orElseThrow { Errors.recordNotFound() }.name
            ReportBuilders.farmReport(range.year, farmName, data.parcels, data.runs, data.tasks, result.summary, Times.now())
        }
        "work" -> workReport(farmId, range)
        "fuel" -> fuelReport(farmId, range)
        "costs" -> costReport(farmId, range)
        "yields" -> yieldReport(farmId, range)
        "rotation" -> rotationReport(farmId, range)
        "irrigation" -> irrigationReport(farmId, range)
        else -> throw Errors.badRequest("Unknown report type.")
    }

    companion object {
        @JvmField
        val REPORT_TYPES: List<String> =
            listOf("costs", "profitability", "farm", "parcels", "work", "machines", "crops", "fuel", "yields", "rotation", "irrigation")

        @JvmField
        val FINANCIAL_REPORTS: Set<String> = setOf("costs", "profitability", "farm")

        private fun day(date: Instant?): String = Times.isoDay(date) ?: ""

        private fun round(value: Double): Double = Js.round(value, 2)

        private fun sum(rows: List<Map<String, Any?>>, key: String): Double =
            round(rows.sumOfDoubles { (it[key] as? Number)?.toDouble() ?: 0.0 })

        private fun row(vararg pairs: Any?): MutableMap<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            for (i in pairs.indices step 2) {
                map[pairs[i] as String] = pairs[i + 1]
            }
            return map
        }

        private fun statusLabel(status: RunStatus): String = when (status) {
            RunStatus.RUNNING -> "Running"
            RunStatus.PAUSED -> "Paused"
            RunStatus.COMPLETED -> "Completed"
            RunStatus.CANCELLED -> "Cancelled"
        }

        private fun periodOf(range: Range): String = day(range.from) + " - " + day(range.to)
    }
}
