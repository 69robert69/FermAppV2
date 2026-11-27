package com.fermapp.api.persistence.entity

import com.fermapp.api.common.util.Times
import com.fermapp.api.domain.model.Role
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

/** The role of a user in a farm. A user can belong to several farms. */
@Entity
@Table(name = "FarmMembership", uniqueConstraints = [UniqueConstraint(columnNames = ["farmId", "userId"])])
class FarmMembership {

    @Id
    @GeneratedValue
    @UuidGenerator
    lateinit var id: UUID

    @Column(nullable = false)
    lateinit var farmId: UUID

    @Column(nullable = false)
    lateinit var userId: UUID

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    lateinit var role: Role

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
