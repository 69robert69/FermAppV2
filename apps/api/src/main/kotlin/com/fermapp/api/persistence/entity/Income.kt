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
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import org.hibernate.annotations.UuidGenerator

/** Revenue realised on a parcel. */
@Entity
@Table(name = "Income")
class Income {

    @Id
    @GeneratedValue
    @UuidGenerator
    lateinit var id: UUID

    @Column(nullable = false)
    lateinit var parcelId: UUID

    @Column(nullable = false)
    var year: Int = 0

    @Column(nullable = false, precision = 12, scale = 2)
    lateinit var amount: BigDecimal

    @Column(nullable = false)
    var currency: String = "EUR"

    var source: String? = null

    @Column(nullable = false)
    var date: Instant? = null

    @Column(nullable = false)
    var createdAt: Instant? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parcelId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    var parcel: Parcel? = null

    @PrePersist
    protected fun onCreate() {
        val now = Times.now()
        if (createdAt == null) {
            createdAt = now
        }
        if (date == null) {
            date = now
        }
    }
}
