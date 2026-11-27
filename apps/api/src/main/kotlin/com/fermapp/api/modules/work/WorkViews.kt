package com.fermapp.api.modules.work

import com.fasterxml.jackson.annotation.JsonUnwrapped
import com.fermapp.api.domain.model.IrrigationSystemType
import com.fermapp.api.domain.model.RunStatus
import com.fermapp.api.domain.model.WorkType
import com.fermapp.api.modules.common.Views.MachineLocationView
import com.fermapp.api.modules.common.Views.MachineRef
import com.fermapp.api.modules.common.Views.ParcelRef
import com.fermapp.api.modules.common.Views.UserRef
import com.fermapp.api.persistence.entity.IrrigationRun
import com.fermapp.api.persistence.entity.WorkSession
import java.time.Instant
import java.util.UUID

/** JSON shapes of field work and irrigation, with their live progress (serializeWork, serializeIrrigation). */
object WorkViews {

    @JvmRecord
    data class WorkView(
        val id: UUID?,
        val farmId: UUID?,
        val parcelId: UUID?,
        val machineId: UUID?,
        val operatorId: UUID?,
        val type: WorkType?,
        val areaHa: Double,
        val workingWidthM: Double,
        val speedKmh: Double,
        val efficiencyPct: Double,
        val capacityHaH: Double,
        val durationHours: Double,
        val status: RunStatus?,
        val startedAt: Instant?,
        val pausedAt: Instant?,
        val pausedMs: Long,
        val completedAt: Instant?,
        val progressPct: Double,
        val fuelLiters: Double?,
        val notes: String?,
        val createdAt: Instant?,
        val updatedAt: Instant?,
        val parcel: ParcelRef?,
        val machine: MachineRef?,
        val operator: UserRef?,
        val progress: RunProgress,
        val areaDoneHa: Double,
    ) {
        companion object {
            @JvmStatic
            fun of(w: WorkSession, now: Instant): WorkView {
                val progress = RunProgress.of(w, now)
                return WorkView(
                    w.id, w.farmId, w.parcelId, w.machineId, w.operatorId, w.type, w.areaHa, w.workingWidthM, w.speedKmh,
                    w.efficiencyPct, w.capacityHaH, w.durationHours, w.status, w.startedAt, w.pausedAt, w.pausedMs,
                    w.completedAt, w.progressPct, w.fuelLiters, w.notes, w.createdAt, w.updatedAt,
                    ParcelRef.of(w.parcel), MachineRef.of(w.machine), UserRef.of(w.operator), progress,
                    Math.round(w.areaHa * progress.percent) / 100.0,
                )
            }
        }
    }

    /** Active work on the map and in the parcel panel also carries the last GPS position. */
    @JvmRecord
    data class WorkWithLocation(@get:JsonUnwrapped val work: WorkView, val lastLocation: MachineLocationView?)

    @JvmRecord
    data class IrrigationView(
        val id: UUID?,
        val farmId: UUID?,
        val parcelId: UUID?,
        val systemType: IrrigationSystemType?,
        val routeLengthM: Double,
        val speedMPerH: Double,
        val doseMm: Double?,
        val durationHours: Double,
        val status: RunStatus?,
        val startedAt: Instant?,
        val pausedAt: Instant?,
        val pausedMs: Long,
        val completedAt: Instant?,
        val progressPct: Double,
        val createdAt: Instant?,
        val updatedAt: Instant?,
        val parcel: ParcelRef?,
        val progress: RunProgress,
    ) {
        companion object {
            @JvmStatic
            fun of(r: IrrigationRun, now: Instant): IrrigationView = IrrigationView(
                r.id, r.farmId, r.parcelId, r.systemType, r.routeLengthM, r.speedMPerH, r.doseMm, r.durationHours, r.status,
                r.startedAt, r.pausedAt, r.pausedMs, r.completedAt, r.progressPct, r.createdAt, r.updatedAt,
                ParcelRef.of(r.parcel), RunProgress.of(r, now),
            )
        }
    }

    /** Active irrigation on the map and in the parcel panel carries a label such as "Pivot #1". */
    @JvmRecord
    data class LabelledIrrigation(@get:JsonUnwrapped val run: IrrigationView, val label: String)

    @JvmStatic
    fun systemLabel(type: IrrigationSystemType): String = when (type) {
        IrrigationSystemType.REEL -> "Hose reel"
        IrrigationSystemType.PIVOT -> "Pivot"
        IrrigationSystemType.LINEAR -> "Linear"
    }

    @JvmStatic
    fun workLabel(type: WorkType): String = when (type) {
        WorkType.PLOWING -> "Ploughing"
        WorkType.DISCING -> "Discing"
        WorkType.SUBSOILING -> "Subsoiling"
        WorkType.SOWING -> "Sowing"
        WorkType.FERTILIZING -> "Fertilizing"
        WorkType.SPRAYING -> "Spraying"
        WorkType.HARVESTING -> "Harvesting"
    }
}
