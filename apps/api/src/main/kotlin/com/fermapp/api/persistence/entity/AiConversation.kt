package com.fermapp.api.persistence.entity

import com.fermapp.api.common.util.Times
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import org.hibernate.annotations.UuidGenerator

/** A conversation with the agronomy assistant, optionally about one parcel. */
@Entity
@Table(name = "AiConversation")
class AiConversation {

    @Id
    @GeneratedValue
    @UuidGenerator
    lateinit var id: UUID

    @Column(nullable = false)
    lateinit var farmId: UUID

    @Column(nullable = false)
    lateinit var userId: UUID

    var parcelId: UUID? = null

    @Column(nullable = false)
    lateinit var title: String

    @Column(nullable = false)
    var createdAt: Instant? = null

    @Column(nullable = false)
    var updatedAt: Instant? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farmId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    var farm: Farm? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "userId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    var user: UserAccount? = null

    @OneToMany(mappedBy = "conversation", fetch = FetchType.LAZY)
    @OrderBy("createdAt ASC")
    var messages: MutableList<AiMessage> = ArrayList()

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
