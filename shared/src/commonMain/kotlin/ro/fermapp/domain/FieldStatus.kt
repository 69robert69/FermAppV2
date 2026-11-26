package ro.fermapp.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant

/*
 * The real state of a parcel, from its plan of work rather than from the last
 * operation: is something running, is anything late, is work due today, is
 * more work coming, or is everything planned done.
 */

/**
 * In this order in charts: blue sits between the warm states so neighbours stay distinct for colour-blind readers.
 * Map colours are red, orange, blue, yellow, green, validated as a set on the app's dark surfaces.
 * Every state also carries an icon and a label, never colour alone.
 */
@Serializable
enum class FieldState(val color: String, val label: String) {
    OVERDUE("#D93C74", "Overdue work"),
    DUE_TODAY("#CF6A10", "Work due today"),
    ACTIVE("#3B8FD6", "Work in progress"),
    SCHEDULED("#A99212", "Work scheduled"),
    COMPLETE("#109C86", "All work completed"),
}

val FIELD_STATES: List<FieldState> = FieldState.entries

/** Work planned within this many days counts as the active period. */
const val ACTIVE_PERIOD_DAYS = 7

@Serializable
enum class TaskStatus { TODO, IN_PROGRESS, DONE }

@Serializable
data class FieldTask(
    val id: String,
    val title: String,
    val type: String? = null,
    val status: TaskStatus,
    val dueDate: String,
)

@Serializable
data class FieldStatus(
    val state: FieldState,
    /** Short badge text, for example "2 tasks left" or "Work scheduled tomorrow". */
    val badge: String,
    val overdue: Int,
    val dueToday: Int,
    /** Open tasks due later within the active period. */
    val upcoming: Int,
    /** Every open task in the active period, late ones included. */
    val pending: Int,
    /** Open tasks due after the active period. */
    val later: Int,
    val nextDue: FieldTask?,
    /** Share of the season's planned tasks that are done, 0-100; null when nothing was planned. */
    val completionScore: Int?,
    val done: Int,
    val total: Int,
)

/** Local midnight of the day containing [date]. */
internal fun startOfLocalDay(date: Instant, zone: TimeZone): Instant {
    val local = date.toLocalDateTime(zone).date
    return local.atStartOfDayIn(zone)
}

private fun LocalDate.plusDays(days: Int, zone: TimeZone): Instant =
    LocalDate.fromEpochDays(toEpochDays() + days).atStartOfDayIn(zone)

private fun plural(count: Int, one: String, many: String) = "$count ${if (count == 1) one else many}"

fun fieldStatus(
    tasks: List<FieldTask>,
    activeWork: Int,
    now: Instant = Clock.System.now(),
    periodDays: Int = ACTIVE_PERIOD_DAYS + 1,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): FieldStatus {
    val todayDate = now.toLocalDateTime(zone).date
    val today = todayDate.atStartOfDayIn(zone)
    val tomorrow = todayDate.plusDays(1, zone)
    val dayAfter = todayDate.plusDays(2, zone)
    val periodEnd = todayDate.plusDays(periodDays, zone)
    val year = todayDate.year

    var overdue = 0
    var dueToday = 0
    var dueTodayNotStarted = 0
    var upcoming = 0
    var later = 0
    var nextDue: FieldTask? = null
    var nextDueAt: Instant? = null
    var done = 0
    var total = 0

    for (task in tasks) {
        val due = parseJsDate(task.dueDate)
        if (due.toLocalDateTime(zone).year == year) {
            total++
            if (task.status == TaskStatus.DONE) done++
        }
        if (task.status == TaskStatus.DONE) continue
        when {
            due < today -> overdue++
            due < tomorrow -> {
                dueToday++
                if (task.status == TaskStatus.TODO) dueTodayNotStarted++
            }
            due < periodEnd -> upcoming++
            else -> later++
        }
        if (due >= today && (nextDueAt == null || due < nextDueAt)) {
            nextDue = task
            nextDueAt = due
        }
    }

    val inProgress = tasks.any { it.status == TaskStatus.IN_PROGRESS && parseJsDate(it.dueDate) < tomorrow }
    val pending = overdue + dueToday + upcoming
    // Lateness needs attention first; then work happening now; then work due today that has not started.
    val state = when {
        overdue > 0 -> FieldState.OVERDUE
        activeWork > 0 || inProgress -> FieldState.ACTIVE
        dueTodayNotStarted > 0 -> FieldState.DUE_TODAY
        pending > 0 -> FieldState.SCHEDULED
        else -> FieldState.COMPLETE
    }

    val nextIsTomorrow = nextDueAt != null && nextDueAt < dayAfter && nextDueAt >= tomorrow
    val badge = when (state) {
        FieldState.OVERDUE -> plural(overdue, "task overdue", "tasks overdue")
        FieldState.ACTIVE -> if (pending > 0) "Work in progress, ${plural(pending, "task", "tasks")} left" else "Work in progress"
        FieldState.DUE_TODAY -> plural(dueToday, "task due today", "tasks due today")
        FieldState.SCHEDULED ->
            if (pending == 1 && nextIsTomorrow) "Work scheduled tomorrow" else plural(pending, "scheduled activity", "scheduled activities")
        FieldState.COMPLETE -> "All work completed"
    }

    return FieldStatus(
        state = state,
        badge = badge,
        overdue = overdue,
        dueToday = dueToday,
        upcoming = upcoming,
        pending = pending,
        later = later,
        nextDue = nextDue,
        completionScore = if (total > 0) jsRoundInt((done.toDouble() / total) * 100) else null,
        done = done,
        total = total,
    )
}

@Serializable
data class ParcelFieldStatus(val parcelId: String, val name: String, val status: FieldStatus)

@Serializable
data class ParcelScore(val parcelId: String, val name: String, val score: Int)

@Serializable
data class FieldStatusSummary(
    val complete: Int,
    val active: Int,
    /** Parcels with work still planned in the active period (due today or later). */
    val pending: Int,
    val overdue: Int,
    /** Open tasks due in the next seven days across the farm. */
    val next7Days: Int,
    val averageScore: Int?,
    val mostAdvanced: List<ParcelScore>,
    /** Late or due today and not started: these need someone now. */
    val urgent: List<ParcelFieldStatus>,
)

fun summarizeFieldStatus(items: List<ParcelFieldStatus>): FieldStatusSummary {
    val scored = items.filter { it.status.completionScore != null }
    return FieldStatusSummary(
        complete = items.count { it.status.state == FieldState.COMPLETE },
        active = items.count { it.status.state == FieldState.ACTIVE },
        pending = items.count { it.status.state == FieldState.SCHEDULED || it.status.state == FieldState.DUE_TODAY },
        overdue = items.count { it.status.state == FieldState.OVERDUE },
        next7Days = items.sumOf { it.status.dueToday + it.status.upcoming },
        averageScore = if (scored.isNotEmpty()) jsRoundInt(scored.sumOf { it.status.completionScore!! }.toDouble() / scored.size) else null,
        mostAdvanced = scored.map { ParcelScore(it.parcelId, it.name, it.status.completionScore!!) }.sortedByDescending { it.score }.take(5),
        urgent = items
            .filter { it.status.state == FieldState.OVERDUE || it.status.state == FieldState.DUE_TODAY }
            .sortedWith(compareByDescending<ParcelFieldStatus> { it.status.overdue }.thenByDescending { it.status.dueToday }),
    )
}

/** Which planned tasks an operation of each type carries out. */
val WORK_TO_TASK_TYPES: Map<String, List<String>> = mapOf(
    "PLOWING" to listOf("SOIL_PREP"),
    "DISCING" to listOf("SOIL_PREP"),
    "SUBSOILING" to listOf("SOIL_PREP"),
    "SOWING" to listOf("SOWING"),
    "FERTILIZING" to listOf("FERTILIZATION"),
    "SPRAYING" to listOf("HERBICIDE", "FUNGICIDE", "INSECTICIDE"),
    "HARVESTING" to listOf("HARVEST"),
)

/** A finished operation may close a task planned up to this many days later. */
private const val EARLY_WINDOW_DAYS = 3

/**
 * The open task a finished operation carries out: same parcel, matching type,
 * due by a few days after the operation, the earliest first.
 */
fun <T> taskForFinishedWork(
    tasks: List<T>,
    workType: String,
    finishedAt: Instant,
    status: (T) -> String,
    dueDate: (T) -> Instant,
    type: (T) -> String,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): T? {
    val types = WORK_TO_TASK_TYPES[workType] ?: emptyList()
    val limit = finishedAt.toLocalDateTime(zone).date.plusDays(EARLY_WINDOW_DAYS + 1, zone)
    return tasks
        .filter { status(it) != "DONE" && type(it) in types && dueDate(it) < limit }
        .minByOrNull { dueDate(it) }
}
