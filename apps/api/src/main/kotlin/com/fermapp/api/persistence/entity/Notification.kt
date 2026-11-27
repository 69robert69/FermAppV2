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
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import org.hibernate.annotations.UuidGenerator
import org.hibernate.type.SqlTypes

/** An in-app notification of one user. */
@Entity
@Table(name = "Notification")
class Notification {

    @Id
    @GeneratedValue
    @UuidGenerator
    lateinit var id: UUID

    @Column(nullable = false)
    lateinit var farmId: UUID

    @Column(nullable = false)
    lateinit var userId: UUID

    @Column(nullable = false)
    lateinit var title: String

    @Column(nullable = false)
    lateinit var body: String

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    var data: JsonNode? = null

    var readAt: Instant? = null

    @Column(nullable = false)
    var createdAt: Instant? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farmId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    var farm: Farm? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "userId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    var user: UserAccount? = null

    @PrePersist
    protected fun onCreate() {
        if (createdAt == null) {
            createdAt = Times.now()
        }
    }
}
