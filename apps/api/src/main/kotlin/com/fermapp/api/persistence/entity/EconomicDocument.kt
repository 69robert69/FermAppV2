package com.fermapp.api.persistence.entity

import com.fasterxml.jackson.databind.JsonNode
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
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import org.hibernate.annotations.UuidGenerator
import org.hibernate.type.SqlTypes

/**
 * Economic data stored as documents: the farm settings (`key = "settings"`) and one
 * sheet per parcel and season (`key = "sheet:<parcelId>:<year>"`). Amounts are in euro.
 */
@Entity
@Table(name = "EconomicDocument", uniqueConstraints = [UniqueConstraint(columnNames = ["farmId", "key"])])
class EconomicDocument {

    @Id
    @GeneratedValue
    @UuidGenerator
    lateinit var id: UUID

    @Column(nullable = false)
    lateinit var farmId: UUID

    @Column(nullable = false)
    lateinit var key: String

    var parcelId: UUID? = null

    var year: Int? = null

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    lateinit var data: JsonNode

    @Column(nullable = false)
    var updatedAt: Instant? = null

    @Column(nullable = false)
    var createdAt: Instant? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farmId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    var farm: Farm? = null

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
