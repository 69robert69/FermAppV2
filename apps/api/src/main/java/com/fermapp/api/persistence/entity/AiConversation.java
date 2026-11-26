package com.fermapp.api.persistence.entity;

import com.fermapp.api.common.util.Times;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UuidGenerator;

/** A conversation with the agronomy assistant, optionally about one parcel. */
@Entity
@Table(name = "AiConversation")
@Getter
@Setter
public class AiConversation {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private UUID farmId;

    @Column(nullable = false)
    private UUID userId;

    private UUID parcelId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farmId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Farm farm;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "userId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private UserAccount user;

    @OneToMany(mappedBy = "conversation", fetch = FetchType.LAZY)
    @OrderBy("createdAt ASC")
    private List<AiMessage> messages = new ArrayList<>();

    @PrePersist
    void onCreate() {
        Instant now = Times.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Times.now();
    }
}
