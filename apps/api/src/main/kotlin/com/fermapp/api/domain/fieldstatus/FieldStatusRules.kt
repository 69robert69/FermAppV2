package com.fermapp.api.domain.fieldstatus

import com.fermapp.api.common.util.Times
import java.time.Instant
import java.time.ZoneId
import java.util.Optional

/**
 * The real state of a parcel, from its plan of work rather than from the last operation
 * (fieldStatus.ts). Days are counted in the server's local time zone, as in JavaScript.
 */
object FieldStatusRules {

    enum class FieldState { OVERDUE, DUE_TODAY, ACTIVE, SCHEDULED, COMPLETE }

    @JvmField
    val LABELS: Map<FieldState, String> = mapOf(
        FieldState.OVERDUE to "Overdue work",
        FieldState.ACTIVE to "Work in progress",
        FieldState.DUE_TODAY to "Work due today",
        FieldState.SCHEDULED to "Work scheduled",
        FieldState.COMPLETE to "All work completed",
    )

    /** Work planned within this many days counts as the active period. */
    const val ACTIVE_PERIOD_DAYS = 7

    /** A task as the status rules read it; [dueDate] is an ISO timestamp. */
    @JvmRecord
    data class FieldTask(
        val id: String?,
        val title: String?,
        val type: String?,
        val status: String?,
        val dueDate: String,
        val parcelId: String?,
    )

    @JvmRecord
    data class FieldStatus(
        val state: FieldState,
        val badge: String,
        val overdue: Int,
        val dueToday: Int,
        val upcoming: Int,
        val pending: Int,
        val later: Int,
        val nextDue: FieldTask?,
        val completionScore: Int?,
        val done: Int,
        val total: Int,
    )

    /** Which planned tasks an operation of each type carries out. */
    @JvmField
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

    private fun startOfLocalDay(now: Instant): Long {
        val zone = ZoneId.systemDefault()
        return now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
    }

    private fun plural(count: Int, one: String, many: String) = "$count ${if (count == 1) one else many}"

    private fun due(task: FieldTask): Long = Instant.parse(task.dueDate).toEpochMilli()

    @JvmStatic
    fun fieldStatus(tasks: List<FieldTask>, activeWork: Int, now: Instant): FieldStatus {
        val today = startOfLocalDay(now)
        val tomorrow = today + Times.DAY_MS
        val periodEnd = today + (ACTIVE_PERIOD_DAYS + 1) * Times.DAY_MS
        val year = Times.localYear(now)

        var overdue = 0
        var dueToday = 0
        var dueTodayNotStarted = 0
        var upcoming = 0
        var later = 0
        var nextDue: FieldTask? = null
        var done = 0
        var total = 0

        for (task in tasks) {
            val due = due(task)
            val isDone = task.status == "DONE"
            if (Times.localYear(Instant.ofEpochMilli(due)) == year) {
                total += 1
                if (isDone) {
                    done += 1
                }
            }
            if (isDone) {
                continue
            }
            when {
                due < today -> overdue += 1
                due < tomorrow -> {
                    dueToday += 1
                    if (task.status == "TODO") {
                        dueTodayNotStarted += 1
                    }
                }
                due < periodEnd -> upcoming += 1
                else -> later += 1
            }
            if (due >= today && (nextDue == null || due < due(nextDue))) {
                nextDue = task
            }
        }

        val inProgress = tasks.any { it.status == "IN_PROGRESS" && due(it) < tomorrow }
        val pending = overdue + dueToday + upcoming
        // Lateness needs attention first; then work happening now; then work due today that has not started.
        val state = when {
            overdue > 0 -> FieldState.OVERDUE
            activeWork > 0 || inProgress -> FieldState.ACTIVE
            dueTodayNotStarted > 0 -> FieldState.DUE_TODAY
            pending > 0 -> FieldState.SCHEDULED
            else -> FieldState.COMPLETE
        }

        val nextIsTomorrow = nextDue != null && due(nextDue) < tomorrow + Times.DAY_MS && due(nextDue) >= tomorrow
        val badge = when (state) {
            FieldState.OVERDUE -> plural(overdue, "task overdue", "tasks overdue")
            FieldState.ACTIVE -> if (pending > 0) "Work in progress, ${plural(pending, "task", "tasks")} left" else "Work in progress"
            FieldState.DUE_TODAY -> plural(dueToday, "task due today", "tasks due today")
            FieldState.SCHEDULED -> if (pending == 1 && nextIsTomorrow) {
                "Work scheduled tomorrow"
            } else {
                plural(pending, "scheduled activity", "scheduled activities")
            }
            FieldState.COMPLETE -> "All work completed"
        }

        val completionScore = if (total > 0) Math.round(done.toDouble() / total * 100).toInt() else null
        return FieldStatus(state, badge, overdue, dueToday, upcoming, pending, later, nextDue, completionScore, done, total)
    }

    /** A task in the shape [taskForFinishedWork] needs. */
    interface OpenTask {
        fun status(): String?

        fun type(): String?

        fun dueDate(): Instant
    }

    /**
     * The open task a finished operation carries out: same parcel, matching type,
     * due by a few days after the operation, the earliest first.
     */
    @JvmStatic
    fun <T : OpenTask> taskForFinishedWork(tasks: List<T>, workType: String?, finishedAt: Instant): Optional<T> {
        val types = WORK_TO_TASK_TYPES[workType].orEmpty()
        val limit = startOfLocalDay(finishedAt) + (EARLY_WINDOW_DAYS + 1) * Times.DAY_MS
        val match = tasks
            .filter { it.status() != "DONE" && it.type() in types && it.dueDate().toEpochMilli() < limit }
            .minByOrNull { it.dueDate() }
        return Optional.ofNullable(match)
    }
}
