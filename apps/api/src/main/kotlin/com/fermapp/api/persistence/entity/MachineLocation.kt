package com.fermapp.api.persistence.entity

import com.fermapp.api.common.util.Times
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction

/** A GPS position sent by the machine during an operation. The id is a BIGSERIAL. */
@Entity
@Table(name = "MachineLocation")
class MachineLocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(nullable = false)
    lateinit var workSessionId: UUID

    var machineId: UUID? = null

    @Column(nullable = false)
    var lat: Double = 0.0

    @Column(nullable = false)
    var lng: Double = 0.0

    var headingDeg: Double? = null
    var speedKmh: Double? = null
    var accuracyM: Double? = null

    @Column(nullable = false)
    var recordedAt: Instant? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workSessionId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    var workSession: WorkSession? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "machineId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.SET_NULL)
    var machine: Machine? = null

    @PrePersist
    protected fun onCreate() {
        if (recordedAt == null) {
            recordedAt = Times.now()
        }
    }
}
