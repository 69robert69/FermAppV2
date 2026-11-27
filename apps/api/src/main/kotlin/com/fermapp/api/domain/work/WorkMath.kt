package com.fermapp.api.domain.work

import com.fermapp.api.common.util.Js
import java.time.Instant

/** Field work and irrigation timing (work.ts and irrigation.ts). */
object WorkMath {

    private const val MS_PER_HOUR = 3_600_000

    /** Implement settings of a field operation. */
    @JvmRecord
    data class WorkParams(val workingWidthM: Double, val speedKmh: Double, val efficiencyPct: Double)

    /** Progress of an operation with known duration. */
    @JvmRecord
    data class TimedProgress(
        val elapsedMs: Double,
        val remainingMs: Double,
        val percent: Double,
        val eta: Instant,
        val finished: Boolean,
    )

    /** `estimateWork`: capacity, duration and estimated end. */
    @JvmRecord
    data class WorkEstimate(val capacityHaH: Double, val durationHours: Double, val eta: Instant)

    /** `estimateIrrigation`: duration and estimated end. */
    @JvmRecord
    data class IrrigationEstimate(val durationHours: Double, val eta: Instant)

    /** Capacity (ha/hour) = Width (m) × Speed (km/h) × Efficiency / 10. */
    @JvmStatic
    fun workCapacityHaH(params: WorkParams): Double {
        require(params.workingWidthM > 0 && params.speedKmh > 0 && params.efficiencyPct > 0 && params.efficiencyPct <= 100) {
            "Width, speed and efficiency (1-100%) must be positive."
        }
        return params.workingWidthM * params.speedKmh * (params.efficiencyPct / 100) / 10
    }

    @JvmStatic
    fun workDurationHours(areaHa: Double, capacityHaH: Double): Double {
        require(areaHa > 0 && capacityHaH > 0) { "Area and capacity must be positive." }
        return areaHa / capacityHaH
    }

    /**
     * Progress of an operation with known duration; pauses stop the timer.
     *
     * @param pausedMs time accumulated in completed pauses
     * @param pausedAt moment the current pause started, if the job is paused
     */
    @JvmStatic
    fun timedProgress(startedAt: Instant, durationMs: Double, pausedMs: Double, pausedAt: Instant?, now: Instant): TimedProgress {
        val reference = pausedAt ?: now
        val elapsedMs = (reference.toEpochMilli() - startedAt.toEpochMilli() - pausedMs).coerceIn(0.0, maxOf(0.0, durationMs))
        val remainingMs = durationMs - elapsedMs
        val percent = if (durationMs > 0) Math.round(elapsedMs / durationMs * 1000) / 10.0 else 100.0
        return TimedProgress(elapsedMs, remainingMs, percent, now.plusMillis(remainingMs.toLong()), remainingMs <= 0)
    }

    @JvmStatic
    fun estimateWork(areaHa: Double, params: WorkParams, startAt: Instant): WorkEstimate {
        val capacityHaH = workCapacityHaH(params)
        val durationHours = workDurationHours(areaHa, capacityHaH)
        return WorkEstimate(
            Js.round2(capacityHaH),
            Js.round2(durationHours),
            startAt.plusMillis((durationHours * MS_PER_HOUR).toLong()),
        )
    }

    @JvmStatic
    fun irrigationDurationHours(routeLengthM: Double, speedMPerH: Double): Double {
        require(routeLengthM > 0 && speedMPerH > 0) { "Path length and speed must be positive." }
        return routeLengthM / speedMPerH
    }

    @JvmStatic
    fun estimateIrrigation(routeLengthM: Double, speedMPerH: Double, startAt: Instant): IrrigationEstimate {
        val durationHours = irrigationDurationHours(routeLengthM, speedMPerH)
        return IrrigationEstimate(Js.round2(durationHours), startAt.plusMillis((durationHours * MS_PER_HOUR).toLong()))
    }
}
