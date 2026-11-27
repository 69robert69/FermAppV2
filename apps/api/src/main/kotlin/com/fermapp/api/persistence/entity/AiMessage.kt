package com.fermapp.api.persistence.entity

import com.fermapp.api.common.util.Times
import com.fermapp.api.domain.model.AiRole
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
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import org.hibernate.annotations.UuidGenerator
import org.hibernate.type.SqlTypes

/** One message of a conversation; `source` is "model" or "rules" for assistant answers. */
@Entity
@Table(name = "AiMessage")
class AiMessage {

    @Id
    @GeneratedValue
    @UuidGenerator
    lateinit var id: UUID

    @Column(nullable = false)
    lateinit var conversationId: UUID

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    lateinit var role: AiRole

    @Column(nullable = false)
    lateinit var content: String

    var source: String? = null

    @Column(nullable = false)
    var createdAt: Instant? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversationId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    var conversation: AiConversation? = null

    @PrePersist
    protected fun onCreate() {
        if (createdAt == null) {
            createdAt = Times.now()
        }
    }
}
