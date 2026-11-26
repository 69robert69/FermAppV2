package ro.fermapp.domain.copilot

import ro.fermapp.domain.CROP_CALENDARS
import ro.fermapp.domain.CalendarPriority
import ro.fermapp.domain.CalendarRun
import ro.fermapp.domain.CalendarTask
import ro.fermapp.domain.Resource
import ro.fermapp.domain.TaskStatus
import ro.fermapp.domain.detectConflicts
import ro.fermapp.domain.jsNum
import ro.fermapp.domain.profile
import kotlin.math.abs
import kotlin.math.max

private const val MAX_INSIGHTS = 12

/** What the copilot notices across the farm without being asked. */
fun farmInsights(snapshot: FarmSnapshot): List<CopilotInsight> {
    val today = snapshot.now.take(10)
    val weather = weatherFacts(snapshot)
    val ctx = Context("", snapshot, today, null, null, EMPTY_MEMORY, true, weather, DateExpression())
    val insights = mutableListOf<CopilotInsight>()
    fun add(kind: InsightKind, severity: Severity, parcelId: String?, message: String, ask: String?, proposal: TaskProposal?) =
        insights.add(CopilotInsight("$kind-${parcelId ?: "farm"}-${insights.size}", severity, kind, parcelId, message, ask, proposal))
    val allFacts = snapshot.parcels.map { parcelFacts(snapshot, it) }

    for (facts in allFacts) {
        val name = facts.parcel.name
        val id = facts.parcel.id
        for (task in facts.overdue.take(2)) {
            val late = daysBetween(task.dueDate, today)
            add(
                InsightKind.OVERDUE, if (late > 3) Severity.CRITICAL else Severity.WARNING, id,
                "$name: “${task.title}” is $late day${if (late > 1) "s" else ""} overdue.",
                "What operations are still pending on $name?", null,
            )
        }
        for (task in facts.upcoming.filter { daysBetween(today, it.dueDate) <= 3 }) {
            val label = COPILOT_TASK_TYPES[task.type]?.label?.lowercase() ?: task.title
            val verb = if (taskIsSpray(task.type)) "should be sprayed" else "has $label due"
            val wording = if (dayLabel(task.dueDate, today) == "today") "today" else "within ${max(1, daysBetween(today, task.dueDate))} days"
            add(InsightKind.UPCOMING, Severity.INFO, id, "$name $verb $wording.", null, null)
            val sensitive = taskIsSpray(task.type) || task.type == "NITROGEN" || task.type == "FERTILIZATION"
            if (weather != null && sensitive && (weather.rain48h >= 10 || weather.maxWind48h > 25) && daysBetween(today, task.dueDate) <= 2) {
                add(
                    InsightKind.WEATHER, Severity.WARNING, id,
                    if (weather.rain48h >= 10) {
                        "Heavy rainfall expected (${jsNum(weather.rain48h)} mm). Consider delaying “${task.title}”."
                    } else {
                        "Wind up to ${jsNum(weather.maxWind48h)} km/h: “${task.title}” may drift or spread unevenly."
                    },
                    if (taskIsSpray(task.type)) "Can I spray $name?" else "When should I fertilize $name?",
                    null,
                )
            }
        }

        val nitrogen = nitrogenStatus(facts)
        val nitrogenDue = nitrogen?.due
        val crop = facts.crop
        if (nitrogenDue != null && nitrogen.done == null && crop != null && facts.open.none { it.type == "NITROGEN" || it.type == "FERTILIZATION" }) {
            val offset = daysBetween(today, nitrogenDue)
            if (offset <= 7) {
                add(
                    InsightKind.FERTILIZATION,
                    if (offset < 0) Severity.WARNING else Severity.INFO,
                    id,
                    if (offset < 0) {
                        "$name: nitrogen top-dressing for ${cropName(crop)} is ${-offset} days late."
                    } else {
                        "$name: nitrogen window for ${cropName(crop)} opens ${dayLabel(nitrogenDue, today)}."
                    },
                    "When should I fertilize $name?",
                    propose(
                        snapshot,
                        ProposalInput(
                            "NITROGEN", id, if (offset < 0) today else nitrogenDue,
                            if (offset < 0) CopilotPriority.HIGH else CopilotPriority.MEDIUM,
                            "${crop.profile.name} top-dressing stage.", 5, "Apply nitrogen fertilizer – $name",
                        ),
                    ),
                )
            }
        }

        val harvest = expectedHarvest(facts)
        if (harvest != null && crop != null && facts.season?.harvestDate == null && facts.open.none { it.type == "HARVEST" }) {
            val offset = daysBetween(today, harvest)
            if (offset in -14..10) {
                add(
                    InsightKind.HARVEST,
                    if (offset <= 0) Severity.WARNING else Severity.INFO,
                    id,
                    if (offset <= 0) {
                        "$name: ${cropName(crop)} should be ready to harvest now."
                    } else {
                        "$name: ${cropName(crop)} harvest window starts ${if (offset <= 7) "next week" else "in $offset days"}."
                    },
                    "When should I harvest $name?",
                    propose(
                        snapshot,
                        ProposalInput("HARVEST", id, if (offset < 0) today else harvest, CopilotPriority.HIGH, "Estimated maturity of ${cropName(crop)}.", 7, "Harvest $name"),
                    ),
                )
            }
        }

        if (weather != null && facts.irrigated && facts.progress?.status == ro.fermapp.domain.CropProgressStatus.GROWING && crop != null) {
            val month = today.substring(5, 7).toInt()
            val calendar = CROP_CALENDARS.getValue(crop)
            val inCritical = month >= calendar.criticalFrom && month <= calendar.criticalTo
            val deficit = roundInt(weather.et0_7d * 1.1 - weather.rain7d)
            if (deficit > 20 && weather.rain48h < 10 && facts.activeRuns.none { it.kind == RunKind.IRRIGATION }) {
                add(
                    InsightKind.IRRIGATION,
                    if (inCritical) Severity.WARNING else Severity.INFO,
                    id,
                    "$name faces a $deficit mm water deficit this week${if (inCritical) " in its critical period" else ""}.",
                    "Should I irrigate $name this week?",
                    propose(snapshot, ProposalInput("IRRIGATION_START", id, today, CopilotPriority.HIGH, "$deficit mm deficit over 7 days.", 2)),
                )
            }
        }
    }

    if (weather != null) {
        if ((weather.minTemp7d ?: 10.0) <= -1) {
            add(InsightKind.WEATHER, Severity.WARNING, null, "Frost down to ${rounded(weather.minTemp7d!!)} °C this week: protect sensitive crops and avoid spraying before frost nights.", null, null)
        }
        if ((weather.maxTemp7d ?: 0.0) >= 34) {
            add(InsightKind.WEATHER, Severity.WARNING, null, "Heat up to ${rounded(weather.maxTemp7d!!)} °C this week: risk of crop heat stress and higher water use.", null, null)
        }
        if (weather.rain48h >= 20) {
            add(
                InsightKind.WEATHER, Severity.WARNING, null,
                "Heavy rainfall expected (${jsNum(weather.rain48h)} mm in 48 hours). Consider delaying field operations.",
                "What operations are scheduled this week?", null,
            )
        }
    }

    // Resource conflicts in the next week: the same rules as the calendar.
    val parcelName = { id: String? -> snapshot.parcels.firstOrNull { it.id == id }?.name ?: "farm-wide" }
    val conflicts = detectConflicts(
        tasks = snapshot.tasks
            .filter { daysBetween(today, it.dueDate) in 0..7 }
            .map {
                CalendarTask(
                    it.id, it.title, it.type, it.parcelId, CalendarPriority.valueOf(it.priority.name), TaskStatus.valueOf(it.status.name),
                    it.dueDate.take(10), it.assigneeId, it.machineId,
                )
            },
        runs = snapshot.runs.map {
            CalendarRun(it.id, it.kind.name, it.type, it.parcelId, it.status.name, it.startDate.take(10), it.finishDate?.take(10), it.machineId, it.operatorId)
        },
        machines = snapshot.machines.map { Resource(it.id, it.name, it.type) },
        members = snapshot.members.map { Resource(it.id, it.name, it.role.name) },
        parcelName = parcelName,
    )
    for (conflict in conflicts) {
        add(InsightKind.MACHINERY, Severity.WARNING, null, "Resource conflict on ${dayLabel(conflict.day, today)}: ${conflict.reason}", "Which tractors are currently idle?", null)
    }

    // Satellite findings that need a visit or a treatment.
    for (facts in allFacts) {
        for (finding in facts.parcel.satellite?.findings ?: emptyList()) {
            if (finding.severity == Severity.INFO) continue
            val task = finding.task
            add(
                InsightKind.SATELLITE, finding.severity, facts.parcel.id, finding.message,
                "What does the satellite show on ${facts.parcel.name}?",
                task?.let {
                    propose(
                        snapshot,
                        ProposalInput(it.type, facts.parcel.id, today, CopilotPriority.valueOf(it.priority.name), it.reason, 3, it.title),
                    )
                },
            )
        }
    }

    if (snapshot.canSeeFinance) {
        val profits = allFacts
            .mapNotNull { facts -> parcelProfitPerHa(facts, ctx)?.takeIf { it.basis != ProfitBasis.ESTIMATE }?.let { facts to it } }
        val area = profits.sumOf { it.first.parcel.areaHa }
        if (profits.size >= 2 && area > 0) {
            val average = profits.sumOf { it.second.value * it.first.parcel.areaHa } / area
            for ((facts, profit) in profits) {
                val gap = if (average != 0.0) ((profit.value - average) / abs(average)) * 100 else 0.0
                if (gap <= -15) {
                    add(
                        InsightKind.PROFIT, Severity.INFO, facts.parcel.id,
                        "${facts.parcel.name} is currently ${rounded(abs(gap))}% less profitable than the farm average.",
                        "How can I reduce costs on ${facts.parcel.name}?", null,
                    )
                }
            }
        }
    }

    val noSoil = allFacts.filter { it.parcel.soil == null }
    if (noSoil.isNotEmpty()) {
        add(
            InsightKind.DATA, Severity.INFO, if (noSoil.size == 1) noSoil.first().parcel.id else null,
            "${noSoil.size} parcel${if (noSoil.size > 1) "s have" else " has"} no soil analysis, so crop and fertilizer advice is less precise.",
            null, null,
        )
    }

    // Severity is declared CRITICAL, WARNING, INFO, so ordinal order is the display order.
    return insights.sortedBy { it.severity.ordinal }.take(MAX_INSIGHTS)
}
