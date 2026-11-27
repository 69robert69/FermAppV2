package com.fermapp.api.persistence.entity

import com.fermapp.api.common.util.Times
import com.fermapp.api.domain.model.SoilStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToOne
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

/** The SoilGrids profile of a parcel; at most one per parcel. */
@Entity
@Table(name = "SoilProfile")
class SoilProfile {

    @Id
    @GeneratedValue
    @UuidGenerator
    lateinit var id: UUID

    @Column(nullable = false, unique = true)
    lateinit var parcelId: UUID

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    var status: SoilStatus = SoilStatus.PENDING

    var ph: Double? = null
    var clayPct: Double? = null
    var sandPct: Double? = null
    var siltPct: Double? = null
    var organicCarbonGKg: Double? = null
    var organicMatterPct: Double? = null
    var waterHoldingMmPerM: Double? = null
    var texture: String? = null
    var soilType: String? = null
    var label: String? = null
    var errorMessage: String? = null
    var fetchedAt: Instant? = null

    @Column(nullable = false)
    var updatedAt: Instant? = null

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parcelId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    var parcel: Parcel? = null

    @PrePersist
    @PreUpdate
    protected fun touch() {
        updatedAt = Times.now()
    }
}
