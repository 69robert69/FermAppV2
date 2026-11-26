package com.fermapp.api.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** A sensor reading. The unique key makes resent readings harmless. The id is a BIGSERIAL. */
@Entity
@Table(name = "IotReading", uniqueConstraints = @UniqueConstraint(columnNames = {"deviceId", "metric", "recordedAt"}))
@Getter
@Setter
public class IotReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID deviceId;

    @Column(nullable = false)
    private String metric;

    @Column(nullable = false)
    private double value;

    private String unit;

    @Column(nullable = false)
    private Instant recordedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deviceId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private IotDevice device;
}
