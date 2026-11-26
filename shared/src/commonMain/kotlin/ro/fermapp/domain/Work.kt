package ro.fermapp.domain

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

@Serializable
data class WorkParams(val workingWidthM: Double, val speedKmh: Double, val efficiencyPct: Double)

/** Capacity (ha/hour) = Width (m) × Speed (km/h) × Efficiency / 10. */
fun workCapacityHaH(params: WorkParams): Double {
    val (workingWidthM, speedKmh, efficiencyPct) = params
    require(workingWidthM > 0 && speedKmh > 0 && efficiencyPct > 0 && efficiencyPct <= 100) {
        "Width, speed and efficiency (1-100%) must be positive."
    }
    return (workingWidthM * speedKmh * (efficiencyPct / 100)) / 10
}

fun workDurationHours(areaHa: Double, capacityHaH: Double): Double {
    require(areaHa > 0 && capacityHaH > 0) { "Area and capacity must be positive." }
    return areaHa / capacityHaH
}

data class TimedProgressInput(
    val startedAt: Instant,
    val durationMs: Long,
    /** Time accumulated in completed pauses. */
    val pausedMs: Long = 0,
    /** Moment the current pause started, if the job is paused. */
    val pausedAt: Instant? = null,
    val now: Instant? = null,
)

data class TimedProgress(
    val elapsedMs: Long,
    val remainingMs: Long,
    val percent: Double,
    val eta: Instant,
    val finished: Boolean,
)

/** Progress of an operation with known duration; pauses stop the timer. */
fun timedProgress(input: TimedProgressInput): TimedProgress {
    val now = input.now ?: Clock.System.now()
    val reference = input.pausedAt ?: now
    val elapsedMs = max(
        0L,
        min(input.durationMs, reference.toEpochMilliseconds() - input.startedAt.toEpochMilliseconds() - input.pausedMs),
    )
    val remainingMs = input.durationMs - elapsedMs
    return TimedProgress(
        elapsedMs = elapsedMs,
        remainingMs = remainingMs,
        percent = if (input.durationMs > 0) jsRound((elapsedMs.toDouble() / input.durationMs) * 1000) / 10 else 100.0,
        eta = now + remainingMs.milliseconds,
        finished = remainingMs <= 0,
    )
}

data class WorkEstimate(val capacityHaH: Double, val durationHours: Double, val eta: Instant)

fun estimateWork(areaHa: Double, params: WorkParams, startAt: Instant = Clock.System.now()): WorkEstimate {
    val capacityHaH = workCapacityHaH(params)
    val durationHours = workDurationHours(areaHa, capacityHaH)
    return WorkEstimate(
        capacityHaH = jsRound(capacityHaH, 2),
        durationHours = jsRound(durationHours, 2),
        eta = startAt.plusJsMillis(durationHours * 3_600_000),
    )
}

fun irrigationDurationHours(routeLengthM: Double, speedMPerH: Double): Double {
    require(routeLengthM > 0 && speedMPerH > 0) { "Path length and speed must be positive." }
    return routeLengthM / speedMPerH
}

data class IrrigationEstimate(val durationHours: Double, val eta: Instant)

fun estimateIrrigation(routeLengthM: Double, speedMPerH: Double, startAt: Instant = Clock.System.now()): IrrigationEstimate {
    val durationHours = irrigationDurationHours(routeLengthM, speedMPerH)
    return IrrigationEstimate(
        durationHours = jsRound(durationHours, 2),
        eta = startAt.plusJsMillis(durationHours * 3_600_000),
    )
}
