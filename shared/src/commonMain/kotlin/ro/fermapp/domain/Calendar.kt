package ro.fermapp.domain

import kotlinx.serialization.Serializable
import ro.fermapp.domain.copilot.COPILOT_TASK_TYPES
import ro.fermapp.domain.copilot.TaskCategory
import ro.fermapp.domain.copilot.addDays
import ro.fermapp.domain.copilot.daysBetween
import ro.fermapp.domain.copilot.weekdayIndex

/*
 * The agricultural calendar: colour-coded operations, resource conflicts,
 * weather warnings, field timelines and the planning dashboard. Days are
 * local calendar days, YYYY-MM-DD.
 */

typealias CalendarCategory = TaskCategory

data class CategoryStyle(
    val label: String,
    /** Validated against the app's dark surfaces; tillage is soil brown, harvest red. */
    val color: String,
    val icon: String,
)

val CALENDAR_CATEGORIES: Map<CalendarCategory, CategoryStyle> = mapOf(
    TaskCategory.TILLAGE to CategoryStyle("Tillage", "#A9774B", "shovel"),
    TaskCategory.PLANTING to CategoryStyle("Planting", "#5FB35A", "seed-outline"),
    TaskCategory.IRRIGATION to CategoryStyle("Irrigation", "#4C9FD6", "water-outline"),
    TaskCategory.FERTILIZATION to CategoryStyle("Fertilization", "#E2C23D", "sprout"),
    TaskCategory.CROP_PROTECTION to CategoryStyle("Crop protection", "#A87BD8", "spray"),
    TaskCategory.HARVEST to CategoryStyle("Harvest", "#E0584A", "barley"),
    TaskCategory.MONITORING to CategoryStyle("Field inspection", "#D9D6C8", "magnify"),
)

val CATEGORY_ORDER: List<CalendarCategory> = listOf(
    TaskCategory.TILLAGE, TaskCategory.PLANTING, TaskCategory.IRRIGATION, TaskCategory.FERTILIZATION,
    TaskCategory.CROP_PROTECTION, TaskCategory.HARVEST, TaskCategory.MONITORING,
)

/** Work types of the operation log, mapped to calendar categories. */
private val WORK_CATEGORY: Map<String, CalendarCategory> = mapOf(
    "PLOWING" to TaskCategory.TILLAGE,
    "DISCING" to TaskCategory.TILLAGE,
    "SUBSOILING" to TaskCategory.TILLAGE,
    "SOWING" to TaskCategory.PLANTING,
    "FERTILIZING" to TaskCategory.FERTILIZATION,
    "SPRAYING" to TaskCategory.CROP_PROTECTION,
    "HARVESTING" to TaskCategory.HARVEST,
    "REEL" to TaskCategory.IRRIGATION,
    "PIVOT" to TaskCategory.IRRIGATION,
    "LINEAR" to TaskCategory.IRRIGATION,
)

fun categoryOf(type: String): CalendarCategory = COPILOT_TASK_TYPES[type]?.category ?: WORK_CATEGORY[type] ?: TaskCategory.MONITORING

// ------------------------------------------------------------------ dates

fun shiftDay(day: String, days: Int) = addDays(day, days)
fun dayDiff(from: String, to: String) = daysBetween(from, to)

/** Monday of the week containing the day. */
fun weekStart(day: String) = shiftDay(day, -((weekdayIndex(day) + 6) % 7))
fun weekDays(day: String) = List(7) { shiftDay(weekStart(day), it) }

/** Weeks (Monday first) covering the month of the day, each with 7 days. */
fun monthGrid(day: String): List<List<String>> {
    val first = "${day.take(7)}-01"
    val month = day.take(7)
    val weeks = mutableListOf<List<String>>()
    var cursor = weekStart(first)
    while (weeks.size < 6) {
        val start = cursor
        val week = List(7) { shiftDay(start, it) }
        if (weeks.isNotEmpty() && week.none { it.take(7) == month }) break
        weeks.add(week)
        cursor = shiftDay(cursor, 7)
    }
    return weeks
}

data class SeasonRange(val start: String, val end: String, val label: String)

/** The farming season around a day: autumn sowing to the next harvest, September to August. */
fun seasonRange(day: String): SeasonRange {
    val year = day.take(4).toInt()
    val startYear = if (day.substring(5, 7).toInt() >= 9) year else year - 1
    return SeasonRange("$startYear-09-01", "${startYear + 1}-08-31", "$startYear/${(startYear + 1).toString().drop(2)}")
}

// ------------------------------------------------------------------ entries

@Serializable
enum class CalendarPriority { LOW, MEDIUM, HIGH, URGENT }

@Serializable
data class CalendarTask(
    val id: String,
    val title: String,
    val type: String,
    val parcelId: String?,
    val priority: CalendarPriority,
    val status: TaskStatus,
    /** Local day. */
    val day: String,
    val assigneeId: String?,
    val machineId: String?,
    val source: String? = null,
)

@Serializable
data class CalendarRun(
    val id: String,
    /** WORK or IRRIGATION. */
    val kind: String,
    val type: String,
    val parcelId: String,
    val status: String,
    val startDay: String,
    val endDay: String?,
    val machineId: String?,
    val operatorId: String?,
)

@Serializable
data class Resource(
    val id: String,
    val name: String,
    /** Machine type, or the member's role. */
    val type: String,
)

@Serializable
data class CalendarDay(
    val date: String,
    val tMinC: Double,
    val tMaxC: Double,
    val precipitationMm: Double,
    val rainProbabilityPct: Double?,
    val windMaxKmh: Double?,
)

fun isOpen(task: CalendarTask) = task.status != TaskStatus.DONE
fun isOverdue(task: CalendarTask, today: String) = isOpen(task) && task.day < today

// ------------------------------------------------------------------ conflicts

@Serializable
enum class ConflictKind { MACHINE, OPERATOR, CAPACITY }

@Serializable
data class ResourceConflict(
    val key: String,
    val kind: ConflictKind,
    val day: String,
    val taskIds: List<String>,
    val title: String,
    val reason: String,
)

private val FIELD_MACHINES = setOf("TRACTOR", "COMBINE", "SPRAYER", "SEEDER", "DISC", "CULTIVATOR", "SUBSOILER", "TRAILER")

/** Same machine or operator in two places on one day, or more jobs than machines of a type. */
fun detectConflicts(
    tasks: List<CalendarTask>,
    runs: List<CalendarRun> = emptyList(),
    machines: List<Resource>,
    members: List<Resource>,
    parcelName: (String?) -> String = { it ?: "farm" },
): List<ResourceConflict> {
    val conflicts = mutableListOf<ResourceConflict>()
    val byDay = linkedMapOf<String, MutableList<CalendarTask>>()
    for (task in tasks.filter(::isOpen)) byDay.getOrPut(task.day) { mutableListOf() }.add(task)

    for ((day, dayTasks) in byDay) {
        val running = runs.filter { it.status == "RUNNING" || it.status == "PAUSED" }
        for (machine in machines) {
            val using = dayTasks.filter { it.machineId == machine.id }
            val runningElsewhere = running.firstOrNull { run ->
                run.machineId == machine.id && run.startDay <= day && (run.endDay ?: day) >= day && using.none { it.parcelId == run.parcelId }
            }
            val places = using.map { it.parcelId }.distinct()
            if (places.size >= 2 || (using.isNotEmpty() && runningElsewhere != null)) {
                conflicts.add(
                    ResourceConflict(
                        key = "machine-${machine.id}-$day",
                        kind = ConflictKind.MACHINE,
                        day = day,
                        taskIds = using.map { it.id },
                        title = "${machine.name} double-booked",
                        reason = if (runningElsewhere != null) {
                            "${machine.name} is still working on ${parcelName(runningElsewhere.parcelId)} while ${using.joinToString(", ") { "“${it.title}”" }} needs it."
                        } else {
                            "${machine.name} is scheduled on ${places.size} fields the same day: ${places.joinToString(", ") { parcelName(it) }}."
                        },
                    ),
                )
            }
        }
        for (member in members) {
            val assigned = dayTasks.filter { it.assigneeId == member.id && (COPILOT_TASK_TYPES[it.type]?.machines?.size ?: 0) > 0 }
            val places = assigned.map { it.parcelId }.distinct()
            if (places.size >= 2) {
                conflicts.add(
                    ResourceConflict(
                        "operator-${member.id}-$day", ConflictKind.OPERATOR, day, assigned.map { it.id },
                        "${member.name} assigned twice",
                        "${member.name} has field work on ${places.size} fields the same day: ${places.joinToString(", ") { parcelName(it) }}.",
                    ),
                )
            }
        }
        // Jobs without a named machine still need one of the right type.
        val demand = linkedMapOf<String, MutableList<CalendarTask>>()
        for (task in dayTasks) {
            if (task.machineId != null) continue
            val type = COPILOT_TASK_TYPES[task.type]?.machines?.firstOrNull() ?: continue
            if (type !in FIELD_MACHINES || type == "TRACTOR") continue
            demand.getOrPut(type) { mutableListOf() }.add(task)
        }
        for ((type, jobs) in demand) {
            val fleet = machines.count { it.type == type }
            val parcels = jobs.map { it.parcelId }.distinct()
            if (parcels.size > fleet) {
                val label = type.lowercase()
                conflicts.add(
                    ResourceConflict(
                        key = "capacity-$type-$day",
                        kind = ConflictKind.CAPACITY,
                        day = day,
                        taskIds = jobs.map { it.id },
                        title = if (fleet > 0) "Not enough ${label}s" else "No $label in the fleet",
                        reason = if (fleet > 0) {
                            "${parcels.size} fields need a $label the same day, but the fleet has $fleet."
                        } else {
                            "${jobs.joinToString(", ") { "“${it.title}”" }} need${if (jobs.size == 1) "s" else ""} a $label, and the fleet has none."
                        },
                    ),
                )
            }
        }
    }
    return conflicts.sortedBy { it.day }
}

// ------------------------------------------------------------------ weather

@Serializable
enum class WarningLevel { CAUTION, WARNING }

@Serializable
data class WeatherWarning(val level: WarningLevel, val title: String, val reason: String)

/** What the forecast means for a planned operation. */
fun weatherWarnings(type: String, taskDay: String, days: List<CalendarDay>): List<WeatherWarning> {
    val day = days.firstOrNull { it.date == taskDay } ?: return emptyList()
    val next = days.firstOrNull { it.date == shiftDay(taskDay, 1) }
    val category = categoryOf(type)
    val warnings = mutableListOf<WeatherWarning>()
    val wind = day.windMaxKmh ?: 0.0
    val rain24 = day.precipitationMm + (next?.precipitationMm ?: 0.0) * 0.5
    fun r(value: Double) = jsNum(jsRound(value))
    if (category == TaskCategory.CROP_PROTECTION || type == "FOLIAR_FEEDING") {
        if (wind > 25) {
            warnings.add(WeatherWarning(WarningLevel.WARNING, "Spraying not recommended", "Wind up to ${r(wind)} km/h causes drift."))
        } else if (wind > 15) {
            warnings.add(WeatherWarning(WarningLevel.CAUTION, "Windy for spraying", "Wind up to ${r(wind)} km/h: spray early morning with drift-reducing nozzles."))
        }
        if (day.precipitationMm >= 1 || (day.rainProbabilityPct ?: 0.0) >= 60) {
            warnings.add(
                WeatherWarning(
                    WarningLevel.WARNING,
                    if (type == "HERBICIDE") "Delay herbicide application" else "Delay the treatment",
                    "Rain expected within 24 hours (${r(day.precipitationMm)} mm, ${day.rainProbabilityPct?.let(::jsNum) ?: "?"}%) would wash the product off.",
                ),
            )
        }
    }
    if (category == TaskCategory.FERTILIZATION && rain24 >= 15) {
        warnings.add(WeatherWarning(WarningLevel.WARNING, "Leaching risk", "${r(rain24)} mm of rain around the application washes nitrogen out."))
    }
    if (category == TaskCategory.HARVEST) {
        if (day.precipitationMm >= 10) {
            warnings.add(WeatherWarning(WarningLevel.WARNING, "Harvest risk", "Heavy rainfall expected (${r(day.precipitationMm)} mm): grain moisture and field access suffer."))
        } else if (day.precipitationMm >= 2) {
            warnings.add(WeatherWarning(WarningLevel.CAUTION, "Wet harvest", "${r(day.precipitationMm)} mm of rain raises grain moisture and drying costs."))
        }
    }
    if ((category == TaskCategory.TILLAGE || category == TaskCategory.PLANTING) && day.precipitationMm >= 10) {
        warnings.add(WeatherWarning(WarningLevel.WARNING, "Soil too wet", "${r(day.precipitationMm)} mm of rain: working wet soil causes compaction."))
    }
    if (category == TaskCategory.PLANTING && day.tMinC <= 2) {
        warnings.add(WeatherWarning(WarningLevel.CAUTION, "Cold for sowing", "Night temperature down to ${r(day.tMinC)} °C slows emergence."))
    }
    if (category == TaskCategory.IRRIGATION && day.precipitationMm >= 10) {
        warnings.add(WeatherWarning(WarningLevel.CAUTION, "Rain will water the field", "${r(day.precipitationMm)} mm of rain is forecast: irrigation may be unnecessary."))
    }
    return warnings
}

/** The nearest day from [from] without a weather warning for the operation, within the forecast. */
fun bestDayFor(type: String, from: String, days: List<CalendarDay>): String? =
    days.firstOrNull { day -> day.date >= from && weatherWarnings(type, day.date, days).all { it.level != WarningLevel.WARNING } }?.date

// ------------------------------------------------------------------ timeline

@Serializable
enum class TimelineState { DONE, RUNNING, PLANNED, OVERDUE }

@Serializable
enum class TimelineSource { TASK, WORK, IRRIGATION, SEASON }

@Serializable
data class TimelineEntry(
    val id: String,
    val day: String,
    val endDay: String?,
    val category: CalendarCategory,
    val label: String,
    val state: TimelineState,
    val source: TimelineSource,
)

data class TimelineSeason(val year: Int, val cropName: String, val sowingDate: String?, val harvestDate: String?)

/** Everything that happened or is planned on one field within a period, in date order. */
fun fieldTimeline(
    parcelId: String,
    tasks: List<CalendarTask>,
    runs: List<CalendarRun>,
    seasons: List<TimelineSeason> = emptyList(),
    from: String,
    to: String,
    today: String,
    labelOf: (String) -> String = { COPILOT_TASK_TYPES[it]?.label ?: it },
): List<TimelineEntry> {
    fun inside(day: String) = day >= from && day <= to
    val entries = mutableListOf<TimelineEntry>()
    for (task in tasks) {
        if (task.parcelId != parcelId || !inside(task.day)) continue
        val state = when {
            task.status == TaskStatus.DONE -> TimelineState.DONE
            task.status == TaskStatus.IN_PROGRESS -> TimelineState.RUNNING
            task.day < today -> TimelineState.OVERDUE
            else -> TimelineState.PLANNED
        }
        entries.add(TimelineEntry("t-${task.id}", task.day, null, categoryOf(task.type), task.title, state, TimelineSource.TASK))
    }
    for (run in runs) {
        if (run.parcelId != parcelId || !inside(run.startDay)) continue
        val taskType = when (run.type) {
            "DISCING" -> "DISKING"
            "FERTILIZING" -> "FERTILIZATION"
            "SPRAYING" -> "HERBICIDE"
            "HARVESTING" -> "HARVEST"
            else -> run.type
        }
        val state = when (run.status) {
            "COMPLETED" -> TimelineState.DONE
            "RUNNING", "PAUSED" -> TimelineState.RUNNING
            else -> TimelineState.DONE
        }
        val source = if (run.kind == "IRRIGATION") TimelineSource.IRRIGATION else TimelineSource.WORK
        entries.add(
            TimelineEntry(
                "r-${run.id}", run.startDay, run.endDay, categoryOf(run.type),
                if (run.kind == "IRRIGATION") "Irrigation" else labelOf(taskType), state, source,
            ),
        )
    }
    for (season in seasons) {
        val sowing = season.sowingDate
        if (sowing != null && inside(sowing)) {
            entries.add(TimelineEntry("s-${season.year}-sow", sowing, null, TaskCategory.PLANTING, "${season.cropName} sown", TimelineState.DONE, TimelineSource.SEASON))
        }
        val harvest = season.harvestDate
        if (harvest != null && inside(harvest)) {
            entries.add(
                TimelineEntry(
                    "s-${season.year}-harvest", harvest, null, TaskCategory.HARVEST, "${season.cropName} harvested",
                    if (harvest <= today) TimelineState.DONE else TimelineState.PLANNED, TimelineSource.SEASON,
                ),
            )
        }
    }
    return entries.sortedWith(compareBy<TimelineEntry> { it.day }.then(localeComparator { it.label }))
}

// ------------------------------------------------------------------ dashboard

data class CalendarDashboard(
    val today: List<CalendarTask>,
    val thisWeek: List<CalendarTask>,
    val delayed: List<CalendarTask>,
    val availableMachines: List<Resource>,
    val availableOperators: List<Resource>,
    val critical: List<CalendarTask>,
)

fun calendarDashboard(tasks: List<CalendarTask>, runs: List<CalendarRun>, machines: List<Resource>, operators: List<Resource>, today: String): CalendarDashboard {
    val open = tasks.filter(::isOpen).sortedBy { it.day }
    val weekEnd = shiftDay(weekStart(today), 6)
    val todays = open.filter { it.day == today }
    val active = runs.filter { it.status == "RUNNING" || it.status == "PAUSED" }
    val busyMachines = (todays.map { it.machineId } + active.map { it.machineId }).filterNotNull().filter { it.isNotEmpty() }.toSet()
    val busyPeople = (todays.map { it.assigneeId } + active.map { it.operatorId }).filterNotNull().filter { it.isNotEmpty() }.toSet()
    return CalendarDashboard(
        today = todays,
        thisWeek = open.filter { it.day >= today && it.day <= weekEnd },
        delayed = open.filter { it.day < today },
        availableMachines = machines.filter { it.id !in busyMachines },
        availableOperators = operators.filter { it.id !in busyPeople },
        critical = open.filter {
            (it.priority == CalendarPriority.HIGH || it.priority == CalendarPriority.URGENT) && it.day >= today && dayDiff(today, it.day) <= 7
        },
    )
}
