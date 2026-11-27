package com.fermapp.api.persistence.entity

import com.fermapp.api.common.util.Times
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.UuidGenerator

/** A user account. The table keeps its original name, "User". */
@Entity
@Table(name = "User")
class UserAccount {

    @Id
    @GeneratedValue
    @UuidGenerator
    lateinit var id: UUID

    @Column(nullable = false, unique = true)
    lateinit var email: String

    @Column(nullable = false)
    lateinit var fullName: String

    @Column(nullable = false)
    lateinit var passwordHash: String

    @Column(nullable = false)
    var createdAt: Instant? = null

    @Column(nullable = false)
    var updatedAt: Instant? = null

    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
    @OrderBy("createdAt ASC")
    var memberships: MutableList<FarmMembership> = ArrayList()

    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
    var refreshTokens: MutableList<RefreshToken> = ArrayList()

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
