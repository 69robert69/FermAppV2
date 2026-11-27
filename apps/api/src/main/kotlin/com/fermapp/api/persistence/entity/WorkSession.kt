package com.fermapp.api.persistence.entity

import com.fermapp.api.common.util.Times
import com.fermapp.api.domain.model.RunStatus
import com.fermapp.api.domain.model.WorkType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import org.hibernate.annotations.UuidGenerator
import org.hibernate.type.SqlTypes

/** A field operation (ploughing, sowing, spraying...) with timed progress. */
@Entity
@Table(name = "WorkSession")
class WorkSession : TimedRun {

    @Id
    @GeneratedValue
    @UuidGenerator
    lateinit var id: UUID

    @Column(nullable = false)
    lateinit var farmId: UUID

    @Column(nullable = false)
    lateinit var parcelId: UUID

    var machineId: UUID? = null

    @Column(nullable = false)
    lateinit var operatorId: UUID

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    lateinit var type: WorkType

    @Column(nullable = false)
    var areaHa: Double = 0.0

    @Column(nullable = false)
    var workingWidthM: Double = 0.0

    @Column(nullable = false)
    var speedKmh: Double = 0.0

    @Column(nullable = false)
    var efficiencyPct: Double = 0.0

    @Column(nullable = false)
    var capacityHaH: Double = 0.0

    @Column(nullable = false)
    override var durationHours: Double = 0.0

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    override var status: RunStatus = RunStatus.RUNNING

    @Column(nullable = false)
    override var startedAt: Instant? = null

    override var pausedAt: Instant? = null

    @Column(nullable = false)
    override var pausedMs: Long = 0

    override var completedAt: Instant? = null

    @Column(nullable = false)
    override var progressPct: Double = 0.0

    var fuelLiters: Double? = null

    var notes: String? = null

    @Column(nullable = false)
    var createdAt: Instant? = null

    @Column(nullable = false)
    var updatedAt: Instant? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farmId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    var farm: Farm? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parcelId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    var parcel: Parcel? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "machineId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.SET_NULL)
    var machine: Machine? = null

    /** ON DELETE RESTRICT: a user with logged work cannot be deleted. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "operatorId", insertable = false, updatable = false)
    var operator: UserAccount? = null

    @PrePersist
    protected fun onCreate() {
        val now = Times.now()
        if (createdAt == null) {
            createdAt = now
        }
        if (startedAt == null) {
            startedAt = now
        }
        updatedAt = now
    }

    @PreUpdate
    protected fun onUpdate() {
        updatedAt = Times.now()
    }
}
