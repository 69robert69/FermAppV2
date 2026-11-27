package com.fermapp.api.persistence.entity

import com.fermapp.api.common.util.Times
import com.fermapp.api.domain.model.AlertSeverity
import com.fermapp.api.domain.model.AlertType
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
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import org.hibernate.annotations.UuidGenerator
import org.hibernate.type.SqlTypes

/** A weather alert. The dedupe key keeps one alert per type and day for each parcel. */
@Entity
@Table(name = "WeatherAlert", uniqueConstraints = [UniqueConstraint(columnNames = ["parcelId", "dedupeKey"])])
class WeatherAlert {

    @Id
    @GeneratedValue
    @UuidGenerator
    lateinit var id: UUID

    @Column(nullable = false)
    lateinit var farmId: UUID

    @Column(nullable = false)
    lateinit var parcelId: UUID

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    lateinit var type: AlertType

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    lateinit var severity: AlertSeverity

    @Column(nullable = false)
    lateinit var title: String

    @Column(nullable = false)
    lateinit var message: String

    /** Local time at the parcel. */
    @Column(nullable = false)
    lateinit var startsAt: String

    @Column(nullable = false)
    lateinit var dedupeKey: String

    @Column(nullable = false)
    lateinit var source: String

    @Column(nullable = false)
    var createdAt: Instant? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farmId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    var farm: Farm? = null

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
