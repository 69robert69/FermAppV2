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
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import org.hibernate.annotations.UuidGenerator

/** Daily weather of a parcel, kept for analysis. */
@Entity
@Table(name = "WeatherData", uniqueConstraints = [UniqueConstraint(columnNames = ["parcelId", "date"])])
class WeatherData {

    @Id
    @GeneratedValue
    @UuidGenerator
    lateinit var id: UUID

    @Column(nullable = false)
    lateinit var parcelId: UUID

    @Column(nullable = false)
    lateinit var date: LocalDate

    @Column(nullable = false)
    var tMaxC: Double = 0.0

    @Column(nullable = false)
    var tMinC: Double = 0.0

    @Column(nullable = false)
    var precipitationMm: Double = 0.0

    var humidityPct: Double? = null
    var windMaxKmh: Double? = null
    var et0Mm: Double? = null

    @Column(nullable = false)
    var source: String = "open-meteo"

    @Column(nullable = false)
    var updatedAt: Instant? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parcelId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    var parcel: Parcel? = null

    @PrePersist
    @PreUpdate
    protected fun touch() {
        updatedAt = Times.now()
    }
}
