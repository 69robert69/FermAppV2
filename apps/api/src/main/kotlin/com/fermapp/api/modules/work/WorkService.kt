package com.fermapp.api.modules.work

import com.fermapp.api.common.error.Errors
import com.fermapp.api.common.util.Js
import com.fermapp.api.common.util.Times
import com.fermapp.api.domain.fieldstatus.FieldStatusRules
import com.fermapp.api.domain.model.Role
import com.fermapp.api.domain.model.RunStatus
import com.fermapp.api.domain.model.TaskStatus
import com.fermapp.api.modules.common.Views.TaskWithParcelName
import com.fermapp.api.modules.notifications.NotificationService
import com.fermapp.api.modules.notifications.NotificationService.NotificationInput
import com.fermapp.api.modules.work.WorkViews.WorkView
import com.fermapp.api.persistence.entity.Machine
import com.fermapp.api.persistence.repository.FarmMembershipRepository
import com.fermapp.api.persistence.repository.TaskRepository
import com.fermapp.api.persistence.repository.WorkSessionRepository
import com.fermapp.api.realtime.RealtimeEvent
import com.fermapp.api.realtime.RealtimeHub
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/** Completing field work: once only, with diesel, the linked task and the team notified (work.service.ts). */
@Service
class WorkService(
    private val works: WorkSessionRepository,
    private val tasks: TaskRepository,
    private val memberships: FarmMembershipRepository,
    private val notifications: NotificationService,
    private val realtime: RealtimeHub,
    private val tx: TransactionTemplate,
) {

    /** Loads the operation with its parcel, machine and operator and builds the JSON with live progress. */
    fun view(id: UUID, now: Instant): WorkView =
        tx.execute { WorkView.of(works.findById(id).orElseThrow { Errors.recordNotFound() }, now) }!!

    fun publish(id: UUID): WorkView {
        val payload = view(id, Times.now())
        realtime.emitToFarm(payload.farmId, RealtimeEvent.WORK_UPDATED, payload)
        return payload
    }

    /**
     * A finished operation carries out the task planned for it: the earliest open task of the
     * matching type on the parcel, due by a few days later.
     */
    private fun completeLinkedTask(farmId: UUID, parcelId: UUID, workType: String, now: Instant) {
        val open = tasks.findByFarmIdAndParcelIdAndStatusNot(farmId, parcelId, TaskStatus.DONE)
        val match = FieldStatusRules.taskForFinishedWork(open, workType, now).orElse(null) ?: return
        val updated = tx.execute {
            val task = tasks.findById(match.id).orElseThrow { Errors.recordNotFound() }
            task.status = TaskStatus.DONE
            task.completedAt = now
            tasks.saveAndFlush(task)
            TaskWithParcelName.of(task)
        }
        realtime.emitToFarm(farmId, RealtimeEvent.TASK_UPDATED, updated)
    }

    private class Snapshot(
        val farmId: UUID,
        val parcelId: UUID,
        val operatorId: UUID?,
        val type: String,
        val label: String,
        val parcelName: String?,
        val progress: RunProgress,
        val fuel: Double?,
    )

    /** Completes the operation only once, even if several server instances are running; null when already ended. */
    fun complete(workId: UUID, now: Instant): WorkView? {
        val snapshot = tx.execute {
            works.findById(workId)
                .filter { it.status in ACTIVE }
                .map { work ->
                    val progress = RunProgress.of(work, now)
                    Snapshot(
                        work.farmId, work.parcelId, work.operatorId, work.type.name, WorkViews.workLabel(work.type),
                        work.parcel!!.name, progress, fuelFor(work.machine, progress.elapsedSeconds),
                    )
                }
                .orElse(null)
        } ?: return null
        if (works.complete(workId, now, snapshot.progress.percent, snapshot.fuel, ACTIVE) == 0) {
            return null
        }
        val completed = view(workId, now)
        realtime.emitToFarm(snapshot.farmId, RealtimeEvent.WORK_UPDATED, completed)
        completeLinkedTask(snapshot.farmId, snapshot.parcelId, snapshot.type, now)

        // The operator comes first; a missing operator stays in the set, as before.
        val recipients = LinkedHashSet<UUID?>()
        recipients += snapshot.operatorId
        memberships.findByFarmIdAndRoleIn(snapshot.farmId, listOf(Role.ADMIN, Role.FARM_MANAGER)).forEach { recipients += it.userId }
        @Suppress("UNCHECKED_CAST")
        notifications.notify(
            NotificationInput(
                snapshot.farmId, recipients as Collection<UUID>, null, "Operation completed",
                snapshot.label + " on parcel " + snapshot.parcelName + ": " + Js.num(snapshot.progress.percent) + "% done.",
                mapOf("type" to "work", "id" to workId.toString()),
            ),
        )
        return completed
    }

    companion object {
        @JvmField
        val ACTIVE: List<RunStatus> = listOf(RunStatus.RUNNING, RunStatus.PAUSED)

        private fun fuelFor(machine: Machine?, elapsedSeconds: Long): Double? {
            val consumption = machine?.avgConsumptionLh
            if (consumption == null || consumption == 0.0) {
                return null
            }
            return Math.round(consumption * elapsedSeconds / 36) / 100.0
        }
    }
}
