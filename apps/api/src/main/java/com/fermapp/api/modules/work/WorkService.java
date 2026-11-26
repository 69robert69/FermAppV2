package com.fermapp.api.modules.work;

import com.fermapp.api.common.error.Errors;
import com.fermapp.api.common.util.Times;
import com.fermapp.api.domain.fieldstatus.FieldStatusRules;
import com.fermapp.api.domain.model.Role;
import com.fermapp.api.domain.model.RunStatus;
import com.fermapp.api.domain.model.TaskStatus;
import com.fermapp.api.modules.common.Views.TaskWithParcelName;
import com.fermapp.api.modules.notifications.NotificationService;
import com.fermapp.api.modules.notifications.NotificationService.NotificationInput;
import com.fermapp.api.modules.work.WorkViews.WorkView;
import com.fermapp.api.persistence.entity.FarmMembership;
import com.fermapp.api.persistence.entity.Machine;
import com.fermapp.api.persistence.entity.Task;
import com.fermapp.api.persistence.entity.WorkSession;
import com.fermapp.api.persistence.repository.FarmMembershipRepository;
import com.fermapp.api.persistence.repository.TaskRepository;
import com.fermapp.api.persistence.repository.WorkSessionRepository;
import com.fermapp.api.realtime.RealtimeEvent;
import com.fermapp.api.realtime.RealtimeHub;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Completing field work: once only, with diesel, the linked task and the team notified (work.service.ts). */
@Service
public class WorkService {

    public static final List<RunStatus> ACTIVE = List.of(RunStatus.RUNNING, RunStatus.PAUSED);

    private final WorkSessionRepository works;
    private final TaskRepository tasks;
    private final FarmMembershipRepository memberships;
    private final NotificationService notifications;
    private final RealtimeHub realtime;
    private final TransactionTemplate tx;

    public WorkService(WorkSessionRepository works, TaskRepository tasks, FarmMembershipRepository memberships,
                       NotificationService notifications, RealtimeHub realtime, TransactionTemplate tx) {
        this.works = works;
        this.tasks = tasks;
        this.memberships = memberships;
        this.notifications = notifications;
        this.realtime = realtime;
        this.tx = tx;
    }

    /** Loads the operation with its parcel, machine and operator and builds the JSON with live progress. */
    public WorkView view(UUID id, Instant now) {
        return tx.execute(status -> WorkView.of(works.findById(id).orElseThrow(Errors::recordNotFound), now));
    }

    public WorkView publish(UUID id) {
        WorkView payload = view(id, Times.now());
        realtime.emitToFarm(payload.farmId(), RealtimeEvent.WORK_UPDATED, payload);
        return payload;
    }

    private static Double fuelFor(Machine machine, long elapsedSeconds) {
        if (machine == null || machine.getAvgConsumptionLh() == null || machine.getAvgConsumptionLh() == 0) {
            return null;
        }
        return Math.round(machine.getAvgConsumptionLh() * elapsedSeconds / 36) / 100.0;
    }

    /**
     * A finished operation carries out the task planned for it: the earliest open task of the
     * matching type on the parcel, due by a few days later.
     */
    private void completeLinkedTask(UUID farmId, UUID parcelId, String workType, Instant now) {
        List<Task> open = tasks.findByFarmIdAndParcelIdAndStatusNot(farmId, parcelId, TaskStatus.DONE);
        Optional<Task> match = FieldStatusRules.taskForFinishedWork(open, workType, now);
        if (match.isEmpty()) {
            return;
        }
        TaskWithParcelName updated = tx.execute(status -> {
            Task task = tasks.findById(match.get().getId()).orElseThrow(Errors::recordNotFound);
            task.setStatus(TaskStatus.DONE);
            task.setCompletedAt(now);
            tasks.saveAndFlush(task);
            return TaskWithParcelName.of(task);
        });
        realtime.emitToFarm(farmId, RealtimeEvent.TASK_UPDATED, updated);
    }

    private record Snapshot(UUID farmId, UUID parcelId, UUID operatorId, String type, String label, String parcelName,
                            RunProgress progress, Double fuel) {
    }

    /** Completes the operation only once, even if several server instances are running; null when already ended. */
    public WorkView complete(UUID workId, Instant now) {
        Snapshot snapshot = tx.execute(status -> works.findById(workId)
                .filter(work -> ACTIVE.contains(work.getStatus()))
                .map(work -> {
                    RunProgress progress = RunProgress.of(work, now);
                    return new Snapshot(work.getFarmId(), work.getParcelId(), work.getOperatorId(), work.getType().name(),
                            WorkViews.workLabel(work.getType()), work.getParcel().getName(), progress,
                            fuelFor(work.getMachine(), progress.elapsedSeconds()));
                })
                .orElse(null));
        if (snapshot == null) {
            return null;
        }
        if (works.complete(workId, now, snapshot.progress().percent(), snapshot.fuel(), ACTIVE) == 0) {
            return null;
        }
        WorkView completed = view(workId, now);
        realtime.emitToFarm(snapshot.farmId(), RealtimeEvent.WORK_UPDATED, completed);
        completeLinkedTask(snapshot.farmId(), snapshot.parcelId(), snapshot.type(), now);

        Set<UUID> recipients = new LinkedHashSet<>();
        recipients.add(snapshot.operatorId());
        memberships.findByFarmIdAndRoleIn(snapshot.farmId(), List.of(Role.ADMIN, Role.FARM_MANAGER))
                .stream().map(FarmMembership::getUserId).forEach(recipients::add);
        notifications.notify(new NotificationInput(snapshot.farmId(), recipients, null, "Operation completed",
                snapshot.label() + " on parcel " + snapshot.parcelName() + ": "
                        + com.fermapp.api.common.util.Js.num(snapshot.progress().percent()) + "% done.",
                Map.of("type", "work", "id", workId.toString())));
        return completed;
    }
}
