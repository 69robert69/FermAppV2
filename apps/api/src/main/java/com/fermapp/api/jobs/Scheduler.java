package com.fermapp.api.jobs;

import com.fermapp.api.common.cache.CacheService;
import com.fermapp.api.common.util.Concurrency;
import com.fermapp.api.common.util.Times;
import com.fermapp.api.config.AppProperties;
import com.fermapp.api.domain.model.RunStatus;
import com.fermapp.api.domain.model.SoilStatus;
import com.fermapp.api.modules.irrigation.IrrigationService;
import com.fermapp.api.modules.notifications.NotificationService;
import com.fermapp.api.modules.notifications.NotificationService.NotificationInput;
import com.fermapp.api.modules.soil.SoilService;
import com.fermapp.api.modules.weather.WeatherService;
import com.fermapp.api.modules.work.RunProgress;
import com.fermapp.api.modules.work.WorkService;
import com.fermapp.api.modules.work.WorkViews.IrrigationView;
import com.fermapp.api.modules.work.WorkViews.WorkView;
import com.fermapp.api.persistence.entity.IrrigationRun;
import com.fermapp.api.persistence.entity.Parcel;
import com.fermapp.api.persistence.entity.Task;
import com.fermapp.api.persistence.entity.WorkSession;
import com.fermapp.api.persistence.repository.IdempotencyRecordRepository;
import com.fermapp.api.persistence.repository.IrrigationRunRepository;
import com.fermapp.api.persistence.repository.MachineLocationRepository;
import com.fermapp.api.persistence.repository.ParcelRepository;
import com.fermapp.api.persistence.repository.TaskRepository;
import com.fermapp.api.persistence.repository.WorkSessionRepository;
import com.fermapp.api.realtime.RealtimeEvent;
import com.fermapp.api.realtime.RealtimeHub;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Background jobs (jobs/scheduler.ts). Each job takes a short lock, which expires before its next
 * run, so only one server instance executes it; a run that is still going skips the next tick.
 * JOBS_ENABLED=false turns them off on instances that must not run them.
 */
@Component
public class Scheduler {

    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);
    private static final long PROGRESS_INTERVAL_MS = 15_000;
    private static final long REMINDER_INTERVAL_MS = 5 * 60_000;
    private static final long WEATHER_INTERVAL_MS = 30 * 60_000;
    private static final long SOIL_RETRY_INTERVAL_MS = 6 * 3_600_000;
    private static final long IDEMPOTENCY_RETENTION_MS = 7 * Times.DAY_MS;
    private static final long LOCATION_RETENTION_MS = 30 * Times.DAY_MS;

    private final AppProperties properties;
    private final CacheService cache;
    private final WorkSessionRepository works;
    private final IrrigationRunRepository runs;
    private final TaskRepository tasks;
    private final ParcelRepository parcels;
    private final IdempotencyRecordRepository idempotency;
    private final MachineLocationRepository locations;
    private final WorkService workService;
    private final IrrigationService irrigationService;
    private final NotificationService notifications;
    private final WeatherService weather;
    private final SoilService soil;
    private final RealtimeHub realtime;
    private final TransactionTemplate tx;
    private final Map<String, AtomicBoolean> running = Map.of(
            "progress", new AtomicBoolean(), "task-reminders", new AtomicBoolean(), "weather-alerts", new AtomicBoolean(),
            "soil-retry", new AtomicBoolean(), "idempotency-clean", new AtomicBoolean());

    public Scheduler(AppProperties properties, CacheService cache, WorkSessionRepository works, IrrigationRunRepository runs,
                     TaskRepository tasks, ParcelRepository parcels, IdempotencyRecordRepository idempotency,
                     MachineLocationRepository locations, WorkService workService, IrrigationService irrigationService,
                     NotificationService notifications, WeatherService weather, SoilService soil, RealtimeHub realtime,
                     TransactionTemplate tx) {
        this.properties = properties;
        this.cache = cache;
        this.works = works;
        this.runs = runs;
        this.tasks = tasks;
        this.parcels = parcels;
        this.idempotency = idempotency;
        this.locations = locations;
        this.workService = workService;
        this.irrigationService = irrigationService;
        this.notifications = notifications;
        this.weather = weather;
        this.soil = soil;
        this.realtime = realtime;
        this.tx = tx;
    }

    private void every(String name, long intervalMs, Runnable job) {
        if (!properties.jobsEnabled()) {
            return;
        }
        AtomicBoolean flag = running.get(name);
        if (!flag.compareAndSet(false, true)) {
            return;
        }
        try {
            if (cache.acquireLock("job:" + name, Math.max(1, intervalMs / 1000 - 1))) {
                job.run();
            }
        } catch (RuntimeException error) {
            log.error("Job failed: {}", name, error);
        } finally {
            flag.set(false);
        }
    }

    @Scheduled(fixedRate = PROGRESS_INTERVAL_MS, initialDelay = PROGRESS_INTERVAL_MS)
    void progressJob() {
        every("progress", PROGRESS_INTERVAL_MS, () -> tickProgress(Times.now()));
    }

    @Scheduled(fixedRate = REMINDER_INTERVAL_MS, initialDelay = REMINDER_INTERVAL_MS)
    void reminderJob() {
        every("task-reminders", REMINDER_INTERVAL_MS, () -> sendTaskReminders(Times.now()));
    }

    @Scheduled(fixedRate = WEATHER_INTERVAL_MS, initialDelay = WEATHER_INTERVAL_MS)
    void weatherJob() {
        every("weather-alerts", WEATHER_INTERVAL_MS, this::checkWeatherAlerts);
    }

    @Scheduled(fixedRate = SOIL_RETRY_INTERVAL_MS, initialDelay = SOIL_RETRY_INTERVAL_MS)
    void soilJob() {
        every("soil-retry", SOIL_RETRY_INTERVAL_MS, this::retrySoilProfiles);
    }

    @Scheduled(fixedRate = SOIL_RETRY_INTERVAL_MS, initialDelay = SOIL_RETRY_INTERVAL_MS)
    void cleanupJob() {
        every("idempotency-clean", SOIL_RETRY_INTERVAL_MS, this::cleanIdempotency);
    }

    /** Sends live progress and completes operations that have reached 100%. */
    public void tickProgress(Instant now) {
        List<WorkSession> runningWork = tx.execute(status -> {
            List<WorkSession> list = works.findByStatus(RunStatus.RUNNING);
            list.forEach(work -> {
                work.getParcel().getName();
                if (work.getMachine() != null) {
                    work.getMachine().getName();
                }
                work.getOperator().getFullName();
            });
            return list;
        });
        for (WorkSession work : runningWork) {
            RunProgress progress = RunProgress.of(work, now);
            if (progress.finished()) {
                workService.complete(work.getId(), now);
            } else {
                works.updateProgress(work.getId(), progress.percent(), now);
                realtime.emitToFarm(work.getFarmId(), RealtimeEvent.WORK_PROGRESS, WorkView.of(work, now));
            }
        }
        List<IrrigationRun> runningIrrigation = tx.execute(status -> {
            List<IrrigationRun> list = runs.findByStatus(RunStatus.RUNNING);
            list.forEach(run -> run.getParcel().getName());
            return list;
        });
        for (IrrigationRun run : runningIrrigation) {
            RunProgress progress = RunProgress.of(run, now);
            if (progress.finished()) {
                irrigationService.complete(run.getId(), now);
            } else {
                runs.updateProgress(run.getId(), progress.percent(), now);
                realtime.emitToFarm(run.getFarmId(), RealtimeEvent.IRRIGATION_PROGRESS, IrrigationView.of(run, now));
            }
        }
    }

    /** Notifies the assignee 24 hours before the deadline. */
    public void sendTaskReminders(Instant now) {
        List<Task> due = tx.execute(status -> {
            List<Task> list = tasks.findDueForReminder(now.plusMillis(Times.DAY_MS), PageRequest.of(0, 500));
            list.forEach(task -> {
                if (task.getParcel() != null) {
                    task.getParcel().getName();
                }
            });
            return list;
        });
        for (Task task : due) {
            if (tasks.markReminderSent(task.getId(), now) == 0) {
                continue;
            }
            notifications.notify(new NotificationInput(task.getFarmId(), List.of(task.getAssigneeId()), null,
                    task.getDueDate().isBefore(now) ? "Task overdue" : "Deadline approaching",
                    task.getTitle() + (task.getParcel() != null ? " on parcel " + task.getParcel().getName() : "") + ".",
                    Map.of("type", "task", "id", task.getId().toString())));
        }
    }

    private void checkWeatherAlerts() {
        List<Parcel> active = parcels.findByArchivedAtIsNull();
        Concurrency.mapLimit(active, 5, parcel -> {
            try {
                weather.checkParcelAlerts(parcel);
            } catch (RuntimeException error) {
                log.warn("Alert check failed: {}", error.getMessage());
            }
        });
    }

    private void retrySoilProfiles() {
        List<Parcel> pending = parcels.findWithSoilStatus(List.of(SoilStatus.PENDING, SoilStatus.FAILED), PageRequest.of(0, 25));
        Concurrency.mapLimit(pending, 2, parcel -> soil.refresh(parcel.getId(), parcel.getCenterLat(), parcel.getCenterLng()));
    }

    private void cleanIdempotency() {
        tx.executeWithoutResult(status -> {
            idempotency.deleteOlderThan(Instant.now().minusMillis(IDEMPOTENCY_RETENTION_MS));
            locations.deleteOlderThan(Instant.now().minusMillis(LOCATION_RETENTION_MS));
        });
    }
}
