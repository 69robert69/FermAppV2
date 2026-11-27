package com.fermapp.api.modules.common

import com.fasterxml.jackson.annotation.JsonUnwrapped
import com.fasterxml.jackson.databind.JsonNode
import com.fermapp.api.common.util.Times
import com.fermapp.api.domain.model.AiRole
import com.fermapp.api.domain.model.AlertSeverity
import com.fermapp.api.domain.model.AlertType
import com.fermapp.api.domain.model.CostCategory
import com.fermapp.api.domain.model.CropCode
import com.fermapp.api.domain.model.IotDeviceKind
import com.fermapp.api.domain.model.IotProtocol
import com.fermapp.api.domain.model.MachineType
import com.fermapp.api.domain.model.SoilStatus
import com.fermapp.api.domain.model.TaskPriority
import com.fermapp.api.domain.model.TaskStatus
import com.fermapp.api.domain.model.TaskType
import com.fermapp.api.persistence.entity.AiConversation
import com.fermapp.api.persistence.entity.AiMessage
import com.fermapp.api.persistence.entity.CostEntry
import com.fermapp.api.persistence.entity.CropSeason
import com.fermapp.api.persistence.entity.Income
import com.fermapp.api.persistence.entity.IotDevice
import com.fermapp.api.persistence.entity.IotReading
import com.fermapp.api.persistence.entity.Machine
import com.fermapp.api.persistence.entity.MachineLocation
import com.fermapp.api.persistence.entity.Notification
import com.fermapp.api.persistence.entity.Observation
import com.fermapp.api.persistence.entity.Parcel
import com.fermapp.api.persistence.entity.Photo
import com.fermapp.api.persistence.entity.SatelliteObservation
import com.fermapp.api.persistence.entity.SoilProfile
import com.fermapp.api.persistence.entity.Task
import com.fermapp.api.persistence.entity.UserAccount
import com.fermapp.api.persistence.entity.WeatherAlert
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * JSON shapes of the stored records, field for field as Prisma serialised them: scalar
 * columns in schema order, `@db.Date` columns as midnight UTC timestamps, BigInt ids as
 * strings and Decimal columns as numbers where the routes converted them (as strings where
 * they did not).
 *
 * Reference fields are nullable: they come from the Lombok getters of the Java entities,
 * which may return null as they did for the Java records.
 */
object Views {

    private fun number(value: BigDecimal?): Double? = value?.toDouble()

    /** Prisma's Decimal.toJSON(): the shortest decimal string. */
    private fun decimalString(value: BigDecimal?): String? = value?.stripTrailingZeros()?.toPlainString()

    // ------------------------------------------------------------------ references

    @JvmRecord
    data class UserRef(val id: UUID?, val fullName: String?) {
        companion object {
            @JvmStatic
            fun of(user: UserAccount?): UserRef? = user?.let { UserRef(it.id, it.fullName) }
        }
    }

    @JvmRecord
    data class PublicUser(val id: UUID?, val email: String?, val fullName: String?) {
        companion object {
            @JvmStatic
            fun of(user: UserAccount): PublicUser = PublicUser(user.id, user.email, user.fullName)
        }
    }

    @JvmRecord
    data class ParcelRef(val id: UUID?, val name: String?, val code: String?) {
        companion object {
            @JvmStatic
            fun of(parcel: Parcel?): ParcelRef? = parcel?.let { ParcelRef(it.id, it.name, it.code) }
        }
    }

    @JvmRecord
    data class ParcelNameRef(val id: UUID?, val name: String?) {
        companion object {
            @JvmStatic
            fun of(parcel: Parcel?): ParcelNameRef? = parcel?.let { ParcelNameRef(it.id, it.name) }
        }
    }

    @JvmRecord
    data class MachineRef(val id: UUID?, val name: String?, val avgConsumptionLh: Double?) {
        companion object {
            @JvmStatic
            fun of(machine: Machine?): MachineRef? = machine?.let { MachineRef(it.id, it.name, it.avgConsumptionLh) }
        }
    }

    // ------------------------------------------------------------------ parcels

    @JvmRecord
    data class ParcelView(
        val id: UUID?,
        val farmId: UUID?,
        val name: String?,
        val code: String?,
        val geometry: JsonNode?,
        val areaHa: Double,
        val perimeterM: Double,
        val centerLat: Double,
        val centerLng: Double,
        val locality: String?,
        val county: String?,
        val notes: String?,
        val irrigatedAreaHa: Double?,
        val lastCrop: String?,
        val plannedCrop: CropCode?,
        val tillageSystem: String?,
        val tillageHistory: JsonNode?,
        val archivedAt: Instant?,
        val createdAt: Instant?,
        val updatedAt: Instant?,
    ) {
        companion object {
            @JvmStatic
            fun of(p: Parcel): ParcelView = ParcelView(
                p.id, p.farmId, p.name, p.code, p.geometry, p.areaHa, p.perimeterM, p.centerLat, p.centerLng, p.locality,
                p.county, p.notes, p.irrigatedAreaHa, p.lastCrop, p.plannedCrop, p.tillageSystem, p.tillageHistory,
                p.archivedAt, p.createdAt, p.updatedAt,
            )
        }
    }

    /**
     * A crop season. The two prices are numbers where the routes used serializeSeason and
     * Decimal strings where Prisma returned the record as it was.
     */
    @JvmRecord
    data class CropSeasonView(
        val id: UUID?,
        val parcelId: UUID?,
        val year: Int,
        val crop: CropCode?,
        val sowingDate: Instant?,
        val expectedHarvestDate: Instant?,
        val harvestDate: Instant?,
        val yieldT: Double?,
        val harvestMoisture: Double?,
        val expectedYieldTHa: Double?,
        val expectedPricePerT: Any?,
        val actualPricePerT: Any?,
        val notes: String?,
        val createdAt: Instant?,
        val updatedAt: Instant?,
    ) {
        companion object {
            @JvmStatic
            fun of(s: CropSeason): CropSeasonView = CropSeasonView(
                s.id, s.parcelId, s.year, s.crop, s.sowingDate, s.expectedHarvestDate, s.harvestDate, s.yieldT,
                s.harvestMoisture, s.expectedYieldTHa, number(s.expectedPricePerT), number(s.actualPricePerT), s.notes,
                s.createdAt, s.updatedAt,
            )

            @JvmStatic
            fun raw(s: CropSeason): CropSeasonView = CropSeasonView(
                s.id, s.parcelId, s.year, s.crop, s.sowingDate, s.expectedHarvestDate, s.harvestDate, s.yieldT,
                s.harvestMoisture, s.expectedYieldTHa, decimalString(s.expectedPricePerT), decimalString(s.actualPricePerT),
                s.notes, s.createdAt, s.updatedAt,
            )
        }
    }

    @JvmRecord
    data class SoilProfileView(
        val id: UUID?,
        val parcelId: UUID?,
        val status: SoilStatus?,
        val ph: Double?,
        val clayPct: Double?,
        val sandPct: Double?,
        val siltPct: Double?,
        val organicCarbonGKg: Double?,
        val organicMatterPct: Double?,
        val waterHoldingMmPerM: Double?,
        val texture: String?,
        val soilType: String?,
        val label: String?,
        val errorMessage: String?,
        val fetchedAt: Instant?,
        val updatedAt: Instant?,
    ) {
        companion object {
            @JvmStatic
            fun of(s: SoilProfile?): SoilProfileView? = s?.let {
                SoilProfileView(
                    it.id, it.parcelId, it.status, it.ph, it.clayPct, it.sandPct, it.siltPct, it.organicCarbonGKg,
                    it.organicMatterPct, it.waterHoldingMmPerM, it.texture, it.soilType, it.label, it.errorMessage,
                    it.fetchedAt, it.updatedAt,
                )
            }
        }
    }

    @JvmRecord
    data class SatelliteView(
        val id: UUID?,
        val parcelId: UUID?,
        val date: Instant?,
        val ndvi: Double,
        val ndre: Double?,
        val healthIndex: Int,
        val healthClass: String?,
        val cloudPct: Double?,
        val source: String?,
        val createdAt: Instant?,
    ) {
        companion object {
            @JvmStatic
            fun of(o: SatelliteObservation): SatelliteView = SatelliteView(
                o.id, o.parcelId, Times.atUtcMidnight(o.date), o.ndvi, o.ndre, o.healthIndex, o.healthClass, o.cloudPct,
                o.source, o.createdAt,
            )
        }
    }

    // ------------------------------------------------------------------ machines

    @JvmRecord
    data class MachineView(
        val id: UUID?,
        val farmId: UUID?,
        val name: String?,
        val type: MachineType?,
        val brand: String?,
        val model: String?,
        val powerHp: Double?,
        val avgConsumptionLh: Double?,
        val tillageSystems: List<String>,
        val operatorId: UUID?,
        val archivedAt: Instant?,
        val createdAt: Instant?,
        val updatedAt: Instant?,
    ) {
        companion object {
            @JvmStatic
            fun of(m: Machine): MachineView = MachineView(
                m.id, m.farmId, m.name, m.type, m.brand, m.model, m.powerHp, m.avgConsumptionLh,
                m.tillageSystems?.asList() ?: listOf(), m.operatorId, m.archivedAt, m.createdAt, m.updatedAt,
            )
        }
    }

    @JvmRecord
    data class MachineWithOperator(@get:JsonUnwrapped val machine: MachineView, val operator: UserRef?) {
        companion object {
            @JvmStatic
            fun of(m: Machine): MachineWithOperator = MachineWithOperator(MachineView.of(m), UserRef.of(m.operator))
        }
    }

    /** A GPS position; the BIGSERIAL id is sent as a string. */
    @JvmRecord
    data class MachineLocationView(
        val id: String?,
        val workSessionId: UUID?,
        val machineId: UUID?,
        val lat: Double,
        val lng: Double,
        val headingDeg: Double?,
        val speedKmh: Double?,
        val accuracyM: Double?,
        val recordedAt: Instant?,
    ) {
        companion object {
            @JvmStatic
            fun of(l: MachineLocation?): MachineLocationView? = l?.let {
                MachineLocationView(
                    it.id.toString(), it.workSessionId, it.machineId, it.lat, it.lng, it.headingDeg, it.speedKmh, it.accuracyM,
                    it.recordedAt,
                )
            }
        }
    }

    // ------------------------------------------------------------------ tasks

    @JvmRecord
    data class TaskView(
        val id: UUID?,
        val farmId: UUID?,
        val parcelId: UUID?,
        val assigneeId: UUID?,
        val planId: UUID?,
        val planStepKey: String?,
        val type: TaskType?,
        val title: String?,
        val description: String?,
        val priority: TaskPriority?,
        val status: TaskStatus?,
        val dueDate: Instant?,
        val reminderSentAt: Instant?,
        val completedAt: Instant?,
        val createdAt: Instant?,
        val updatedAt: Instant?,
    ) {
        companion object {
            @JvmStatic
            fun of(t: Task): TaskView = TaskView(
                t.id, t.farmId, t.parcelId, t.assigneeId, t.planId, t.planStepKey, t.type, t.title, t.description, t.priority,
                t.status, t.dueDate, t.reminderSentAt, t.completedAt, t.createdAt, t.updatedAt,
            )
        }
    }

    /** A task with `parcel { id, name, code }` and `assignee { id, fullName }`. */
    @JvmRecord
    data class TaskWithRefs(@get:JsonUnwrapped val task: TaskView, val parcel: ParcelRef?, val assignee: UserRef?) {
        companion object {
            @JvmStatic
            fun of(t: Task): TaskWithRefs = TaskWithRefs(TaskView.of(t), ParcelRef.of(t.parcel), UserRef.of(t.assignee))
        }
    }

    /** A task with only `assignee { id, fullName }`. */
    @JvmRecord
    data class TaskWithAssignee(@get:JsonUnwrapped val task: TaskView, val assignee: UserRef?) {
        companion object {
            @JvmStatic
            fun of(t: Task): TaskWithAssignee = TaskWithAssignee(TaskView.of(t), UserRef.of(t.assignee))
        }
    }

    /** A task with `parcel { id, name }` and `assignee { id, fullName }`. */
    @JvmRecord
    data class TaskWithParcelName(@get:JsonUnwrapped val task: TaskView, val parcel: ParcelNameRef?, val assignee: UserRef?) {
        companion object {
            @JvmStatic
            fun of(t: Task): TaskWithParcelName =
                TaskWithParcelName(TaskView.of(t), ParcelNameRef.of(t.parcel), UserRef.of(t.assignee))
        }
    }

    // ------------------------------------------------------------------ weather, costs, field records

    @JvmRecord
    data class AlertView(
        val id: UUID?,
        val farmId: UUID?,
        val parcelId: UUID?,
        val type: AlertType?,
        val severity: AlertSeverity?,
        val title: String?,
        val message: String?,
        val startsAt: String?,
        val dedupeKey: String?,
        val source: String?,
        val createdAt: Instant?,
    ) {
        companion object {
            @JvmStatic
            fun of(a: WeatherAlert): AlertView = AlertView(
                a.id, a.farmId, a.parcelId, a.type, a.severity, a.title, a.message, a.startsAt, a.dedupeKey, a.source, a.createdAt,
            )
        }
    }

    @JvmRecord
    data class AlertWithParcel(@get:JsonUnwrapped val alert: AlertView, val parcel: ParcelNameRef?) {
        companion object {
            @JvmStatic
            fun of(a: WeatherAlert): AlertWithParcel = AlertWithParcel(AlertView.of(a), ParcelNameRef.of(a.parcel))
        }
    }

    /** A recorded expense, with the amount as a number. */
    @JvmRecord
    data class CostView(
        val id: UUID?,
        val farmId: UUID?,
        val parcelId: UUID?,
        val year: Int,
        val category: CostCategory?,
        val amount: Double,
        val currency: String?,
        val date: Instant?,
        val notes: String?,
        val createdAt: Instant?,
        val updatedAt: Instant?,
    ) {
        companion object {
            @JvmStatic
            fun of(c: CostEntry): CostView = CostView(
                c.id, c.farmId, c.parcelId, c.year, c.category, c.amount.toDouble(), c.currency, c.date, c.notes, c.createdAt,
                c.updatedAt,
            )
        }
    }

    @JvmRecord
    data class IncomeView(
        val id: UUID?,
        val parcelId: UUID?,
        val year: Int,
        val amount: Double,
        val currency: String?,
        val source: String?,
        val date: Instant?,
        val createdAt: Instant?,
    ) {
        companion object {
            @JvmStatic
            fun of(i: Income): IncomeView =
                IncomeView(i.id, i.parcelId, i.year, i.amount.toDouble(), i.currency, i.source, i.date, i.createdAt)
        }
    }

    @JvmRecord
    data class ObservationView(
        val id: UUID?,
        val parcelId: UUID?,
        val authorId: UUID?,
        val text: String?,
        val lat: Double?,
        val lng: Double?,
        val createdAt: Instant?,
        val author: UserRef?,
    ) {
        companion object {
            @JvmStatic
            fun of(o: Observation): ObservationView =
                ObservationView(o.id, o.parcelId, o.authorId, o.text, o.lat, o.lng, o.createdAt, UserRef.of(o.author))
        }
    }

    @JvmRecord
    data class PhotoView(
        val id: UUID?,
        val parcelId: UUID?,
        val observationId: UUID?,
        val authorId: UUID?,
        val storageKey: String?,
        val contentType: String?,
        val sizeBytes: Int,
        val notes: String?,
        val createdAt: Instant?,
    ) {
        companion object {
            @JvmStatic
            fun of(p: Photo): PhotoView = PhotoView(
                p.id, p.parcelId, p.observationId, p.authorId, p.storageKey, p.contentType, p.sizeBytes, p.notes, p.createdAt,
            )
        }
    }

    @JvmRecord
    data class NotificationView(
        val id: UUID?,
        val farmId: UUID?,
        val userId: UUID?,
        val title: String?,
        val body: String?,
        val data: JsonNode?,
        val readAt: Instant?,
        val createdAt: Instant?,
    ) {
        companion object {
            @JvmStatic
            fun of(n: Notification): NotificationView =
                NotificationView(n.id, n.farmId, n.userId, n.title, n.body, n.data, n.readAt, n.createdAt)
        }
    }

    // ------------------------------------------------------------------ IoT and assistant

    /** A device without its key hash. */
    @JvmRecord
    data class IotDeviceView(
        val id: UUID?,
        val farmId: UUID?,
        val parcelId: UUID?,
        val name: String?,
        val kind: IotDeviceKind?,
        val vendor: String?,
        val protocol: IotProtocol?,
        val lastSeenAt: Instant?,
        val createdAt: Instant?,
        val updatedAt: Instant?,
    ) {
        companion object {
            @JvmStatic
            fun of(d: IotDevice): IotDeviceView = IotDeviceView(
                d.id, d.farmId, d.parcelId, d.name, d.kind, d.vendor, d.protocol, d.lastSeenAt, d.createdAt, d.updatedAt,
            )
        }
    }

    /** A reading; the BIGSERIAL id is sent as a string. */
    @JvmRecord
    data class IotReadingView(
        val id: String?,
        val deviceId: UUID?,
        val metric: String?,
        val value: Double,
        val unit: String?,
        val recordedAt: Instant?,
    ) {
        companion object {
            @JvmStatic
            fun of(r: IotReading): IotReadingView =
                IotReadingView(r.id.toString(), r.deviceId, r.metric, r.value, r.unit, r.recordedAt)
        }
    }

    @JvmRecord
    data class ConversationView(
        val id: UUID?,
        val farmId: UUID?,
        val userId: UUID?,
        val parcelId: UUID?,
        val title: String?,
        val createdAt: Instant?,
        val updatedAt: Instant?,
    ) {
        companion object {
            @JvmStatic
            fun of(c: AiConversation): ConversationView =
                ConversationView(c.id, c.farmId, c.userId, c.parcelId, c.title, c.createdAt, c.updatedAt)
        }
    }

    @JvmRecord
    data class AiMessageView(
        val id: UUID?,
        val conversationId: UUID?,
        val role: AiRole?,
        val content: String?,
        val source: String?,
        val createdAt: Instant?,
    ) {
        companion object {
            @JvmStatic
            fun of(m: AiMessage): AiMessageView = AiMessageView(m.id, m.conversationId, m.role, m.content, m.source, m.createdAt)
        }
    }
}
