package com.fermapp.api.modules.irrigation;

import com.fermapp.api.common.error.Errors;
import com.fermapp.api.common.util.Js;
import com.fermapp.api.common.util.Times;
import com.fermapp.api.domain.model.Role;
import com.fermapp.api.modules.notifications.NotificationService;
import com.fermapp.api.modules.notifications.NotificationService.NotificationInput;
import com.fermapp.api.modules.work.RunProgress;
import com.fermapp.api.modules.work.WorkService;
import com.fermapp.api.modules.work.WorkViews.IrrigationView;
import com.fermapp.api.persistence.repository.IrrigationRunRepository;
import com.fermapp.api.realtime.RealtimeEvent;
import com.fermapp.api.realtime.RealtimeHub;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Completing irrigation once and notifying the team (irrigation.service.ts). */
@Service
public class IrrigationService {

    private final IrrigationRunRepository runs;
    private final NotificationService notifications;
    private final RealtimeHub realtime;
    private final TransactionTemplate tx;

    public IrrigationService(IrrigationRunRepository runs, NotificationService notifications, RealtimeHub realtime,
                             TransactionTemplate tx) {
        this.runs = runs;
        this.notifications = notifications;
        this.realtime = realtime;
        this.tx = tx;
    }

    public IrrigationView view(UUID id, Instant now) {
        return tx.execute(status -> IrrigationView.of(runs.findById(id).orElseThrow(Errors::recordNotFound), now));
    }

    public IrrigationView publish(UUID id) {
        IrrigationView payload = view(id, Times.now());
        realtime.emitToFarm(payload.farmId(), RealtimeEvent.IRRIGATION_UPDATED, payload);
        return payload;
    }

    private record Snapshot(UUID farmId, String parcelName, RunProgress progress) {
    }

    /** Completes the irrigation only once; null when it has already ended. */
    public IrrigationView complete(UUID id, Instant now) {
        Snapshot snapshot = tx.execute(status -> runs.findById(id)
                .filter(run -> WorkService.ACTIVE.contains(run.getStatus()))
                .map(run -> new Snapshot(run.getFarmId(), run.getParcel().getName(), RunProgress.of(run, now)))
                .orElse(null));
        if (snapshot == null) {
            return null;
        }
        if (runs.complete(id, now, snapshot.progress().percent(), WorkService.ACTIVE) == 0) {
            return null;
        }
        IrrigationView completed = view(id, now);
        realtime.emitToFarm(snapshot.farmId(), RealtimeEvent.IRRIGATION_UPDATED, completed);
        notifications.notify(new NotificationInput(snapshot.farmId(), null, List.of(Role.ADMIN, Role.FARM_MANAGER, Role.OPERATOR),
                "Irrigation completed", "Irrigation on parcel " + snapshot.parcelName() + " has ended: "
                        + Js.num(snapshot.progress().percent()) + "% done.",
                Map.of("type", "irrigation", "id", id.toString())));
        return completed;
    }
}
