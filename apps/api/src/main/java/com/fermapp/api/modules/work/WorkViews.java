package com.fermapp.api.modules.work;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fermapp.api.domain.model.IrrigationSystemType;
import com.fermapp.api.domain.model.RunStatus;
import com.fermapp.api.domain.model.WorkType;
import com.fermapp.api.modules.common.Views.MachineLocationView;
import com.fermapp.api.modules.common.Views.MachineRef;
import com.fermapp.api.modules.common.Views.ParcelRef;
import com.fermapp.api.modules.common.Views.UserRef;
import com.fermapp.api.persistence.entity.IrrigationRun;
import com.fermapp.api.persistence.entity.WorkSession;
import java.time.Instant;
import java.util.UUID;

/** JSON shapes of field work and irrigation, with their live progress (serializeWork, serializeIrrigation). */
public final class WorkViews {

    private WorkViews() {
    }

    public record WorkView(UUID id, UUID farmId, UUID parcelId, UUID machineId, UUID operatorId, WorkType type, double areaHa,
                           double workingWidthM, double speedKmh, double efficiencyPct, double capacityHaH,
                           double durationHours, RunStatus status, Instant startedAt, Instant pausedAt, long pausedMs,
                           Instant completedAt, double progressPct, Double fuelLiters, String notes, Instant createdAt,
                           Instant updatedAt, ParcelRef parcel, MachineRef machine, UserRef operator, RunProgress progress,
                           double areaDoneHa) {

        public static WorkView of(WorkSession w, Instant now) {
            RunProgress progress = RunProgress.of(w, now);
            return new WorkView(w.getId(), w.getFarmId(), w.getParcelId(), w.getMachineId(), w.getOperatorId(), w.getType(),
                    w.getAreaHa(), w.getWorkingWidthM(), w.getSpeedKmh(), w.getEfficiencyPct(), w.getCapacityHaH(),
                    w.getDurationHours(), w.getStatus(), w.getStartedAt(), w.getPausedAt(), w.getPausedMs(),
                    w.getCompletedAt(), w.getProgressPct(), w.getFuelLiters(), w.getNotes(), w.getCreatedAt(), w.getUpdatedAt(),
                    ParcelRef.of(w.getParcel()), MachineRef.of(w.getMachine()), UserRef.of(w.getOperator()), progress,
                    Math.round(w.getAreaHa() * progress.percent()) / 100.0);
        }
    }

    /** Active work on the map and in the parcel panel also carries the last GPS position. */
    public record WorkWithLocation(@JsonUnwrapped WorkView work, MachineLocationView lastLocation) {
    }

    public record IrrigationView(UUID id, UUID farmId, UUID parcelId, IrrigationSystemType systemType, double routeLengthM,
                                 double speedMPerH, Double doseMm, double durationHours, RunStatus status, Instant startedAt,
                                 Instant pausedAt, long pausedMs, Instant completedAt, double progressPct, Instant createdAt,
                                 Instant updatedAt, ParcelRef parcel, RunProgress progress) {

        public static IrrigationView of(IrrigationRun r, Instant now) {
            return new IrrigationView(r.getId(), r.getFarmId(), r.getParcelId(), r.getSystemType(), r.getRouteLengthM(),
                    r.getSpeedMPerH(), r.getDoseMm(), r.getDurationHours(), r.getStatus(), r.getStartedAt(), r.getPausedAt(),
                    r.getPausedMs(), r.getCompletedAt(), r.getProgressPct(), r.getCreatedAt(), r.getUpdatedAt(),
                    ParcelRef.of(r.getParcel()), RunProgress.of(r, now));
        }
    }

    /** Active irrigation on the map and in the parcel panel carries a label such as "Pivot #1". */
    public record LabelledIrrigation(@JsonUnwrapped IrrigationView run, String label) {
    }

    public static String systemLabel(IrrigationSystemType type) {
        return switch (type) {
            case REEL -> "Hose reel";
            case PIVOT -> "Pivot";
            case LINEAR -> "Linear";
        };
    }

    public static String workLabel(WorkType type) {
        return switch (type) {
            case PLOWING -> "Ploughing";
            case DISCING -> "Discing";
            case SUBSOILING -> "Subsoiling";
            case SOWING -> "Sowing";
            case FERTILIZING -> "Fertilizing";
            case SPRAYING -> "Spraying";
            case HARVESTING -> "Harvesting";
        };
    }
}
