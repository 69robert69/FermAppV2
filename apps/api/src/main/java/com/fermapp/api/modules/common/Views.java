package com.fermapp.api.modules.common;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.JsonNode;
import com.fermapp.api.common.util.Times;
import com.fermapp.api.domain.model.AiRole;
import com.fermapp.api.domain.model.AlertSeverity;
import com.fermapp.api.domain.model.AlertType;
import com.fermapp.api.domain.model.CostCategory;
import com.fermapp.api.domain.model.CropCode;
import com.fermapp.api.domain.model.IotDeviceKind;
import com.fermapp.api.domain.model.IotProtocol;
import com.fermapp.api.domain.model.MachineType;
import com.fermapp.api.domain.model.SoilStatus;
import com.fermapp.api.domain.model.TaskPriority;
import com.fermapp.api.domain.model.TaskStatus;
import com.fermapp.api.domain.model.TaskType;
import com.fermapp.api.persistence.entity.AiConversation;
import com.fermapp.api.persistence.entity.AiMessage;
import com.fermapp.api.persistence.entity.CostEntry;
import com.fermapp.api.persistence.entity.CropSeason;
import com.fermapp.api.persistence.entity.Income;
import com.fermapp.api.persistence.entity.IotDevice;
import com.fermapp.api.persistence.entity.IotReading;
import com.fermapp.api.persistence.entity.Machine;
import com.fermapp.api.persistence.entity.MachineLocation;
import com.fermapp.api.persistence.entity.Notification;
import com.fermapp.api.persistence.entity.Observation;
import com.fermapp.api.persistence.entity.Parcel;
import com.fermapp.api.persistence.entity.Photo;
import com.fermapp.api.persistence.entity.SatelliteObservation;
import com.fermapp.api.persistence.entity.SoilProfile;
import com.fermapp.api.persistence.entity.Task;
import com.fermapp.api.persistence.entity.UserAccount;
import com.fermapp.api.persistence.entity.WeatherAlert;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * JSON shapes of the stored records, field for field as Prisma serialised them: scalar
 * columns in schema order, {@code @db.Date} columns as midnight UTC timestamps, BigInt ids as
 * strings and Decimal columns as numbers where the routes converted them (as strings where
 * they did not).
 */
public final class Views {

    private Views() {
    }

    private static Double number(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    /** Prisma's Decimal.toJSON(): the shortest decimal string. */
    private static String decimalString(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    // ------------------------------------------------------------------ references

    public record UserRef(UUID id, String fullName) {
        public static UserRef of(UserAccount user) {
            return user == null ? null : new UserRef(user.getId(), user.getFullName());
        }
    }

    public record PublicUser(UUID id, String email, String fullName) {
        public static PublicUser of(UserAccount user) {
            return new PublicUser(user.getId(), user.getEmail(), user.getFullName());
        }
    }

    public record ParcelRef(UUID id, String name, String code) {
        public static ParcelRef of(Parcel parcel) {
            return parcel == null ? null : new ParcelRef(parcel.getId(), parcel.getName(), parcel.getCode());
        }
    }

    public record ParcelNameRef(UUID id, String name) {
        public static ParcelNameRef of(Parcel parcel) {
            return parcel == null ? null : new ParcelNameRef(parcel.getId(), parcel.getName());
        }
    }

    public record MachineRef(UUID id, String name, Double avgConsumptionLh) {
        public static MachineRef of(Machine machine) {
            return machine == null ? null : new MachineRef(machine.getId(), machine.getName(), machine.getAvgConsumptionLh());
        }
    }

    // ------------------------------------------------------------------ parcels

    public record ParcelView(UUID id, UUID farmId, String name, String code, JsonNode geometry, double areaHa,
                             double perimeterM, double centerLat, double centerLng, String locality, String county,
                             String notes, Double irrigatedAreaHa, String lastCrop, CropCode plannedCrop,
                             String tillageSystem, JsonNode tillageHistory, Instant archivedAt, Instant createdAt,
                             Instant updatedAt) {
        public static ParcelView of(Parcel p) {
            return new ParcelView(p.getId(), p.getFarmId(), p.getName(), p.getCode(), p.getGeometry(), p.getAreaHa(),
                    p.getPerimeterM(), p.getCenterLat(), p.getCenterLng(), p.getLocality(), p.getCounty(), p.getNotes(),
                    p.getIrrigatedAreaHa(), p.getLastCrop(), p.getPlannedCrop(), p.getTillageSystem(), p.getTillageHistory(),
                    p.getArchivedAt(), p.getCreatedAt(), p.getUpdatedAt());
        }
    }

    /**
     * A crop season. The two prices are numbers where the routes used serializeSeason and
     * Decimal strings where Prisma returned the record as it was.
     */
    public record CropSeasonView(UUID id, UUID parcelId, int year, CropCode crop, Instant sowingDate,
                                 Instant expectedHarvestDate, Instant harvestDate, Double yieldT, Double harvestMoisture,
                                 Double expectedYieldTHa, Object expectedPricePerT, Object actualPricePerT, String notes,
                                 Instant createdAt, Instant updatedAt) {
        public static CropSeasonView of(CropSeason s) {
            return new CropSeasonView(s.getId(), s.getParcelId(), s.getYear(), s.getCrop(), s.getSowingDate(),
                    s.getExpectedHarvestDate(), s.getHarvestDate(), s.getYieldT(), s.getHarvestMoisture(),
                    s.getExpectedYieldTHa(), number(s.getExpectedPricePerT()), number(s.getActualPricePerT()), s.getNotes(),
                    s.getCreatedAt(), s.getUpdatedAt());
        }

        public static CropSeasonView raw(CropSeason s) {
            return new CropSeasonView(s.getId(), s.getParcelId(), s.getYear(), s.getCrop(), s.getSowingDate(),
                    s.getExpectedHarvestDate(), s.getHarvestDate(), s.getYieldT(), s.getHarvestMoisture(),
                    s.getExpectedYieldTHa(), decimalString(s.getExpectedPricePerT()), decimalString(s.getActualPricePerT()),
                    s.getNotes(), s.getCreatedAt(), s.getUpdatedAt());
        }
    }

    public record SoilProfileView(UUID id, UUID parcelId, SoilStatus status, Double ph, Double clayPct, Double sandPct,
                                  Double siltPct, Double organicCarbonGKg, Double organicMatterPct, Double waterHoldingMmPerM,
                                  String texture, String soilType, String label, String errorMessage, Instant fetchedAt,
                                  Instant updatedAt) {
        public static SoilProfileView of(SoilProfile s) {
            return s == null ? null : new SoilProfileView(s.getId(), s.getParcelId(), s.getStatus(), s.getPh(), s.getClayPct(),
                    s.getSandPct(), s.getSiltPct(), s.getOrganicCarbonGKg(), s.getOrganicMatterPct(), s.getWaterHoldingMmPerM(),
                    s.getTexture(), s.getSoilType(), s.getLabel(), s.getErrorMessage(), s.getFetchedAt(), s.getUpdatedAt());
        }
    }

    public record SatelliteView(UUID id, UUID parcelId, Instant date, double ndvi, Double ndre, int healthIndex,
                                String healthClass, Double cloudPct, String source, Instant createdAt) {
        public static SatelliteView of(SatelliteObservation o) {
            return new SatelliteView(o.getId(), o.getParcelId(), Times.atUtcMidnight(o.getDate()), o.getNdvi(), o.getNdre(),
                    o.getHealthIndex(), o.getHealthClass(), o.getCloudPct(), o.getSource(), o.getCreatedAt());
        }
    }

    // ------------------------------------------------------------------ machines

    public record MachineView(UUID id, UUID farmId, String name, MachineType type, String brand, String model, Double powerHp,
                              Double avgConsumptionLh, List<String> tillageSystems, UUID operatorId, Instant archivedAt,
                              Instant createdAt, Instant updatedAt) {
        public static MachineView of(Machine m) {
            return new MachineView(m.getId(), m.getFarmId(), m.getName(), m.getType(), m.getBrand(), m.getModel(), m.getPowerHp(),
                    m.getAvgConsumptionLh(), m.getTillageSystems() == null ? List.of() : Arrays.asList(m.getTillageSystems()),
                    m.getOperatorId(), m.getArchivedAt(), m.getCreatedAt(), m.getUpdatedAt());
        }
    }

    public record MachineWithOperator(@JsonUnwrapped MachineView machine, UserRef operator) {
        public static MachineWithOperator of(Machine m) {
            return new MachineWithOperator(MachineView.of(m), UserRef.of(m.getOperator()));
        }
    }

    /** A GPS position; the BIGSERIAL id is sent as a string. */
    public record MachineLocationView(String id, UUID workSessionId, UUID machineId, double lat, double lng, Double headingDeg,
                                      Double speedKmh, Double accuracyM, Instant recordedAt) {
        public static MachineLocationView of(MachineLocation l) {
            return l == null ? null : new MachineLocationView(String.valueOf(l.getId()), l.getWorkSessionId(), l.getMachineId(),
                    l.getLat(), l.getLng(), l.getHeadingDeg(), l.getSpeedKmh(), l.getAccuracyM(), l.getRecordedAt());
        }
    }

    // ------------------------------------------------------------------ tasks

    public record TaskView(UUID id, UUID farmId, UUID parcelId, UUID assigneeId, UUID planId, String planStepKey, TaskType type,
                           String title, String description, TaskPriority priority, TaskStatus status, Instant dueDate,
                           Instant reminderSentAt, Instant completedAt, Instant createdAt, Instant updatedAt) {
        public static TaskView of(Task t) {
            return new TaskView(t.getId(), t.getFarmId(), t.getParcelId(), t.getAssigneeId(), t.getPlanId(), t.getPlanStepKey(),
                    t.getType(), t.getTitle(), t.getDescription(), t.getPriority(), t.getStatus(), t.getDueDate(),
                    t.getReminderSentAt(), t.getCompletedAt(), t.getCreatedAt(), t.getUpdatedAt());
        }
    }

    /** A task with {@code parcel { id, name, code }} and {@code assignee { id, fullName }}. */
    public record TaskWithRefs(@JsonUnwrapped TaskView task, ParcelRef parcel, UserRef assignee) {
        public static TaskWithRefs of(Task t) {
            return new TaskWithRefs(TaskView.of(t), ParcelRef.of(t.getParcel()), UserRef.of(t.getAssignee()));
        }
    }

    /** A task with only {@code assignee { id, fullName }}. */
    public record TaskWithAssignee(@JsonUnwrapped TaskView task, UserRef assignee) {
        public static TaskWithAssignee of(Task t) {
            return new TaskWithAssignee(TaskView.of(t), UserRef.of(t.getAssignee()));
        }
    }

    /** A task with {@code parcel { id, name }} and {@code assignee { id, fullName }}. */
    public record TaskWithParcelName(@JsonUnwrapped TaskView task, ParcelNameRef parcel, UserRef assignee) {
        public static TaskWithParcelName of(Task t) {
            return new TaskWithParcelName(TaskView.of(t), ParcelNameRef.of(t.getParcel()), UserRef.of(t.getAssignee()));
        }
    }

    // ------------------------------------------------------------------ weather, costs, field records

    public record AlertView(UUID id, UUID farmId, UUID parcelId, AlertType type, AlertSeverity severity, String title,
                            String message, String startsAt, String dedupeKey, String source, Instant createdAt) {
        public static AlertView of(WeatherAlert a) {
            return new AlertView(a.getId(), a.getFarmId(), a.getParcelId(), a.getType(), a.getSeverity(), a.getTitle(),
                    a.getMessage(), a.getStartsAt(), a.getDedupeKey(), a.getSource(), a.getCreatedAt());
        }
    }

    public record AlertWithParcel(@JsonUnwrapped AlertView alert, ParcelNameRef parcel) {
        public static AlertWithParcel of(WeatherAlert a) {
            return new AlertWithParcel(AlertView.of(a), ParcelNameRef.of(a.getParcel()));
        }
    }

    /** A recorded expense, with the amount as a number. */
    public record CostView(UUID id, UUID farmId, UUID parcelId, int year, CostCategory category, double amount, String currency,
                           Instant date, String notes, Instant createdAt, Instant updatedAt) {
        public static CostView of(CostEntry c) {
            return new CostView(c.getId(), c.getFarmId(), c.getParcelId(), c.getYear(), c.getCategory(), c.getAmount().doubleValue(),
                    c.getCurrency(), c.getDate(), c.getNotes(), c.getCreatedAt(), c.getUpdatedAt());
        }
    }

    public record IncomeView(UUID id, UUID parcelId, int year, double amount, String currency, String source, Instant date,
                             Instant createdAt) {
        public static IncomeView of(Income i) {
            return new IncomeView(i.getId(), i.getParcelId(), i.getYear(), i.getAmount().doubleValue(), i.getCurrency(),
                    i.getSource(), i.getDate(), i.getCreatedAt());
        }
    }

    public record ObservationView(UUID id, UUID parcelId, UUID authorId, String text, Double lat, Double lng, Instant createdAt,
                                  UserRef author) {
        public static ObservationView of(Observation o) {
            return new ObservationView(o.getId(), o.getParcelId(), o.getAuthorId(), o.getText(), o.getLat(), o.getLng(),
                    o.getCreatedAt(), UserRef.of(o.getAuthor()));
        }
    }

    public record PhotoView(UUID id, UUID parcelId, UUID observationId, UUID authorId, String storageKey, String contentType,
                            int sizeBytes, String notes, Instant createdAt) {
        public static PhotoView of(Photo p) {
            return new PhotoView(p.getId(), p.getParcelId(), p.getObservationId(), p.getAuthorId(), p.getStorageKey(),
                    p.getContentType(), p.getSizeBytes(), p.getNotes(), p.getCreatedAt());
        }
    }

    public record NotificationView(UUID id, UUID farmId, UUID userId, String title, String body, JsonNode data, Instant readAt,
                                   Instant createdAt) {
        public static NotificationView of(Notification n) {
            return new NotificationView(n.getId(), n.getFarmId(), n.getUserId(), n.getTitle(), n.getBody(), n.getData(),
                    n.getReadAt(), n.getCreatedAt());
        }
    }

    // ------------------------------------------------------------------ IoT and assistant

    /** A device without its key hash. */
    public record IotDeviceView(UUID id, UUID farmId, UUID parcelId, String name, IotDeviceKind kind, String vendor,
                                IotProtocol protocol, Instant lastSeenAt, Instant createdAt, Instant updatedAt) {
        public static IotDeviceView of(IotDevice d) {
            return new IotDeviceView(d.getId(), d.getFarmId(), d.getParcelId(), d.getName(), d.getKind(), d.getVendor(),
                    d.getProtocol(), d.getLastSeenAt(), d.getCreatedAt(), d.getUpdatedAt());
        }
    }

    /** A reading; the BIGSERIAL id is sent as a string. */
    public record IotReadingView(String id, UUID deviceId, String metric, double value, String unit, Instant recordedAt) {
        public static IotReadingView of(IotReading r) {
            return new IotReadingView(String.valueOf(r.getId()), r.getDeviceId(), r.getMetric(), r.getValue(), r.getUnit(),
                    r.getRecordedAt());
        }
    }

    public record ConversationView(UUID id, UUID farmId, UUID userId, UUID parcelId, String title, Instant createdAt,
                                   Instant updatedAt) {
        public static ConversationView of(AiConversation c) {
            return new ConversationView(c.getId(), c.getFarmId(), c.getUserId(), c.getParcelId(), c.getTitle(), c.getCreatedAt(),
                    c.getUpdatedAt());
        }
    }

    public record AiMessageView(UUID id, UUID conversationId, AiRole role, String content, String source, Instant createdAt) {
        public static AiMessageView of(AiMessage m) {
            return new AiMessageView(m.getId(), m.getConversationId(), m.getRole(), m.getContent(), m.getSource(), m.getCreatedAt());
        }
    }
}
