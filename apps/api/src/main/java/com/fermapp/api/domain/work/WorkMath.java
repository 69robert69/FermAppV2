package com.fermapp.api.domain.work;

import com.fermapp.api.common.util.Js;
import java.time.Instant;

/** Field work and irrigation timing (work.ts and irrigation.ts). */
public final class WorkMath {

    /** Implement settings of a field operation. */
    public record WorkParams(double workingWidthM, double speedKmh, double efficiencyPct) {
    }

    /** Progress of an operation with known duration. */
    public record TimedProgress(double elapsedMs, double remainingMs, double percent, Instant eta, boolean finished) {
    }

    /** {@code estimateWork}: capacity, duration and estimated end. */
    public record WorkEstimate(double capacityHaH, double durationHours, Instant eta) {
    }

    /** {@code estimateIrrigation}: duration and estimated end. */
    public record IrrigationEstimate(double durationHours, Instant eta) {
    }

    private WorkMath() {
    }

    /** Capacity (ha/hour) = Width (m) × Speed (km/h) × Efficiency / 10. */
    public static double workCapacityHaH(WorkParams params) {
        if (params.workingWidthM() <= 0 || params.speedKmh() <= 0 || params.efficiencyPct() <= 0 || params.efficiencyPct() > 100) {
            throw new IllegalArgumentException("Width, speed and efficiency (1-100%) must be positive.");
        }
        return params.workingWidthM() * params.speedKmh() * (params.efficiencyPct() / 100) / 10;
    }

    public static double workDurationHours(double areaHa, double capacityHaH) {
        if (areaHa <= 0 || capacityHaH <= 0) {
            throw new IllegalArgumentException("Area and capacity must be positive.");
        }
        return areaHa / capacityHaH;
    }

    /**
     * Progress of an operation with known duration; pauses stop the timer.
     *
     * @param pausedMs time accumulated in completed pauses
     * @param pausedAt moment the current pause started, if the job is paused
     */
    public static TimedProgress timedProgress(Instant startedAt, double durationMs, double pausedMs, Instant pausedAt, Instant now) {
        Instant reference = pausedAt != null ? pausedAt : now;
        double elapsedMs = Math.max(0, Math.min(durationMs, reference.toEpochMilli() - startedAt.toEpochMilli() - pausedMs));
        double remainingMs = durationMs - elapsedMs;
        double percent = durationMs > 0 ? Math.round(elapsedMs / durationMs * 1000) / 10.0 : 100;
        return new TimedProgress(elapsedMs, remainingMs, percent, now.plusMillis((long) remainingMs), remainingMs <= 0);
    }

    public static WorkEstimate estimateWork(double areaHa, WorkParams params, Instant startAt) {
        double capacityHaH = workCapacityHaH(params);
        double durationHours = workDurationHours(areaHa, capacityHaH);
        return new WorkEstimate(Js.round2(capacityHaH), Js.round2(durationHours),
                startAt.plusMillis((long) (durationHours * 3_600_000)));
    }

    public static double irrigationDurationHours(double routeLengthM, double speedMPerH) {
        if (routeLengthM <= 0 || speedMPerH <= 0) {
            throw new IllegalArgumentException("Path length and speed must be positive.");
        }
        return routeLengthM / speedMPerH;
    }

    public static IrrigationEstimate estimateIrrigation(double routeLengthM, double speedMPerH, Instant startAt) {
        double durationHours = irrigationDurationHours(routeLengthM, speedMPerH);
        return new IrrigationEstimate(Js.round2(durationHours), startAt.plusMillis((long) (durationHours * 3_600_000)));
    }
}
