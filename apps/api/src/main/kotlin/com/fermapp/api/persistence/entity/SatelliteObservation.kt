package com.fermapp.api.persistence.entity

import com.fermapp.api.common.util.Times
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import org.hibernate.annotations.UuidGenerator

/** One Sentinel-2 observation of a parcel: NDVI, NDRE and the health index. */
@Entity
@Table(name = "SatelliteObservation", uniqueConstraints = [UniqueConstraint(columnNames = ["parcelId", "date"])])
class SatelliteObservation {

    @Id
    @GeneratedValue
    @UuidGenerator
    lateinit var id: UUID

    @Column(nullable = false)
    lateinit var parcelId: UUID

    @Column(nullable = false)
    lateinit var date: LocalDate

    @Column(nullable = false)
    var ndvi: Double = 0.0

    var ndre: Double? = null

    @Column(nullable = false)
    var healthIndex: Int = 0

    @Column(nullable = false)
    lateinit var healthClass: String

    var cloudPct: Double? = null

    @Column(nullable = false)
    var source: String = "sentinel-2-l2a"

    @Column(nullable = false)
    var createdAt: Instant? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parcelId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    var parcel: Parcel? = null

    @PrePersist
    protected fun onCreate() {
        if (createdAt == null) {
            createdAt = Times.now()
        }
    }
}
