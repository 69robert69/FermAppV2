package com.fermapp.api.persistence.entity

import com.fasterxml.jackson.databind.JsonNode
import com.fermapp.api.common.util.Times
import com.fermapp.api.domain.model.CropCode
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.OneToOne
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import org.hibernate.annotations.UuidGenerator
import org.hibernate.type.SqlTypes

/** A parcel: a GeoJSON polygon in WGS84 with its computed area, perimeter and centre. */
@Entity
@Table(name = "Parcel", uniqueConstraints = [UniqueConstraint(columnNames = ["farmId", "code"])])
class Parcel {

    @Id
    @GeneratedValue
    @UuidGenerator
    lateinit var id: UUID

    @Column(nullable = false)
    lateinit var farmId: UUID

    @Column(nullable = false)
    lateinit var name: String

    @Column(nullable = false)
    lateinit var code: String

    /** GeoJSON polygon in WGS84. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    lateinit var geometry: JsonNode

    @Column(nullable = false)
    var areaHa: Double = 0.0

    @Column(nullable = false)
    var perimeterM: Double = 0.0

    @Column(nullable = false)
    var centerLat: Double = 0.0

    @Column(nullable = false)
    var centerLng: Double = 0.0

    var locality: String? = null

    var county: String? = null

    var notes: String? = null

    /** Area equipped for irrigation, in hectares. */
    var irrigatedAreaHa: Double? = null

    /** Last crop declared at setup: a crop code or OTHER. */
    var lastCrop: String? = null

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    var plannedCrop: CropCode? = null

    /** Soil tillage technology in use: CONVENTIONAL, MINIMUM_TILL, STRIP_TILL or NO_TILL. */
    var tillageSystem: String? = null

    /** One entry per year: [{ year, system }], oldest first. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    var tillageHistory: JsonNode? = null

    var archivedAt: Instant? = null

    @Column(nullable = false)
    var createdAt: Instant? = null

    @Column(nullable = false)
    var updatedAt: Instant? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farmId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    var farm: Farm? = null

    @OneToMany(mappedBy = "parcel", fetch = FetchType.LAZY)
    var seasons: MutableList<CropSeason> = ArrayList()

    @OneToOne(mappedBy = "parcel", fetch = FetchType.LAZY)
    var soilProfile: SoilProfile? = null

    @OneToMany(mappedBy = "parcel", fetch = FetchType.LAZY)
    var workSessions: MutableList<WorkSession> = ArrayList()

    @OneToMany(mappedBy = "parcel", fetch = FetchType.LAZY)
    var tasks: MutableList<Task> = ArrayList()

    @OneToMany(mappedBy = "parcel", fetch = FetchType.LAZY)
    var satellite: MutableList<SatelliteObservation> = ArrayList()

    @OneToMany(mappedBy = "parcel", fetch = FetchType.LAZY)
    var plans: MutableList<ParcelPlan> = ArrayList()

    @PrePersist
    protected fun onCreate() {
        val now = Times.now()
        if (createdAt == null) {
            createdAt = now
        }
        updatedAt = now
    }

    @PreUpdate
    protected fun onUpdate() {
        updatedAt = Times.now()
    }
}
