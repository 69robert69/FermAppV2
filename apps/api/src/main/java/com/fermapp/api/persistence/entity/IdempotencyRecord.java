package com.fermapp.api.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fermapp.api.common.util.Times;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * The saved response of an operation sent with an Idempotency-Key header. A statusCode of 0
 * means the operation is still being processed.
 */
@Entity
@Table(name = "IdempotencyRecord", uniqueConstraints = @UniqueConstraint(columnNames = {"userId", "key"}))
@Getter
@Setter
public class IdempotencyRecord {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String key;

    @Column(nullable = false)
    private String route;

    @Column(nullable = false)
    private int statusCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode response;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Times.now();
        }
    }
}
