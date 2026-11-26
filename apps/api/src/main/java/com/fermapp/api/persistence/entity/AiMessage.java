package com.fermapp.api.persistence.entity;

import com.fermapp.api.common.util.Times;
import com.fermapp.api.domain.model.AiRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/** One message of a conversation; {@code source} is "model" or "rules" for assistant answers. */
@Entity
@Table(name = "AiMessage")
@Getter
@Setter
public class AiMessage {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private UUID conversationId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private AiRole role;

    @Column(nullable = false)
    private String content;

    private String source;

    @Column(nullable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversationId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private AiConversation conversation;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Times.now();
        }
    }
}
