package com.fermapp.api.persistence.entity

import com.fasterxml.jackson.databind.JsonNode
import com.fermapp.api.common.util.Times
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.UuidGenerator
import org.hibernate.type.SqlTypes

/**
 * The saved response of an operation sent with an Idempotency-Key header. A statusCode of 0
 * means the operation is still being processed.
 */
@Entity
@Table(name = "IdempotencyRecord", uniqueConstraints = [UniqueConstraint(columnNames = ["userId", "key"])])
class IdempotencyRecord {

    @Id
    @GeneratedValue
    @UuidGenerator
    lateinit var id: UUID

    @Column(nullable = false)
    lateinit var userId: UUID

    @Column(nullable = false)
    lateinit var key: String

    @Column(nullable = false)
    lateinit var route: String

    @Column(nullable = false)
    var statusCode: Int = 0

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    var response: JsonNode? = null

    @Column(nullable = false)
    var createdAt: Instant? = null

    @PrePersist
    protected fun onCreate() {
        if (createdAt == null) {
            createdAt = Times.now()
        }
    }
}
