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
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import org.hibernate.annotations.UuidGenerator

/**
 * A refresh token, stored as a SHA-256 hash. Tokens rotated from the same login share a
 * family, so reusing an old token revokes the whole family.
 */
@Entity
@Table(name = "RefreshToken")
class RefreshToken {

    @Id
    @GeneratedValue
    @UuidGenerator
    lateinit var id: UUID

    @Column(nullable = false)
    lateinit var userId: UUID

    @Column(nullable = false)
    lateinit var farmId: UUID

    @Column(nullable = false, unique = true)
    lateinit var tokenHash: String

    @Column(nullable = false)
    lateinit var familyId: UUID

    @Column(nullable = false)
    lateinit var expiresAt: Instant

    var revokedAt: Instant? = null

    @Column(nullable = false)
    var createdAt: Instant? = null

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
