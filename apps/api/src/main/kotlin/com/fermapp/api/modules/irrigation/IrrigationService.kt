package com.fermapp.api.modules.irrigation

import com.fermapp.api.common.error.Errors
import com.fermapp.api.common.util.Js
import com.fermapp.api.common.util.Times
import com.fermapp.api.domain.model.Role
import com.fermapp.api.modules.notifications.NotificationService
import com.fermapp.api.modules.notifications.NotificationService.NotificationInput
import com.fermapp.api.modules.work.RunProgress
import com.fermapp.api.modules.work.WorkService
import com.fermapp.api.modules.work.WorkViews.IrrigationView
import com.fermapp.api.persistence.repository.IrrigationRunRepository
import com.fermapp.api.realtime.RealtimeEvent
import com.fermapp.api.realtime.RealtimeHub
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/** Completing irrigation once and notifying the team (irrigation.service.ts). */
@Service
class IrrigationService(
    private val runs: IrrigationRunRepository,
    private val notifications: NotificationService,
    private val realtime: RealtimeHub,
    private val tx: TransactionTemplate,
) {

    fun view(id: UUID, now: Instant): IrrigationView =
        tx.execute { IrrigationView.of(runs.findById(id).orElseThrow { Errors.recordNotFound() }, now) }!!

    fun publish(id: UUID): IrrigationView {
        val payload = view(id, Times.now())
        realtime.emitToFarm(payload.farmId, RealtimeEvent.IRRIGATION_UPDATED, payload)
        return payload
    }

    private class Snapshot(val farmId: UUID, val parcelName: String?, val progress: RunProgress)

    /** Completes the irrigation only once; null when it has already ended. */
    fun complete(id: UUID, now: Instant): IrrigationView? {
        val snapshot = tx.execute {
            runs.findById(id)
                .filter { it.status in WorkService.ACTIVE }
                .map { Snapshot(it.farmId, it.parcel.name, RunProgress.of(it, now)) }
                .orElse(null)
        } ?: return null
        if (runs.complete(id, now, snapshot.progress.percent, WorkService.ACTIVE) == 0) {
            return null
        }
        val completed = view(id, now)
        realtime.emitToFarm(snapshot.farmId, RealtimeEvent.IRRIGATION_UPDATED, completed)
        notifications.notify(
            NotificationInput(
                snapshot.farmId, null, listOf(Role.ADMIN, Role.FARM_MANAGER, Role.OPERATOR), "Irrigation completed",
                "Irrigation on parcel " + snapshot.parcelName + " has ended: " + Js.num(snapshot.progress.percent) + "% done.",
                mapOf("type" to "irrigation", "id" to id.toString()),
            ),
        )
        return completed
    }
}
