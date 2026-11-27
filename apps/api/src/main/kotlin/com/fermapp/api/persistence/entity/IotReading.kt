package com.fermapp.api.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction

/** A sensor reading. The unique key makes resent readings harmless. The id is a BIGSERIAL. */
@Entity
@Table(name = "IotReading", uniqueConstraints = [UniqueConstraint(columnNames = ["deviceId", "metric", "recordedAt"])])
class IotReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(nullable = false)
    lateinit var deviceId: UUID

    @Column(nullable = false)
    lateinit var metric: String

    @Column(nullable = false)
    var value: Double = 0.0

    var unit: String? = null

    @Column(nullable = false)
    var recordedAt: Instant? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deviceId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    var device: IotDevice? = null
}
