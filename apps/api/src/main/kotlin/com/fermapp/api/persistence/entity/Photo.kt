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

/** A parcel photo; the file itself lives in the upload directory under `storageKey`. */
@Entity
@Table(name = "Photo")
class Photo {

    @Id
    @GeneratedValue
    @UuidGenerator
    lateinit var id: UUID

    @Column(nullable = false)
    lateinit var parcelId: UUID

    var observationId: UUID? = null

    @Column(nullable = false)
    lateinit var authorId: UUID

    @Column(nullable = false, unique = true)
    lateinit var storageKey: String

    @Column(nullable = false)
    lateinit var contentType: String

    @Column(nullable = false)
    var sizeBytes: Int = 0

    var notes: String? = null

    @Column(nullable = false)
    var createdAt: Instant? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parcelId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    var parcel: Parcel? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "observationId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.SET_NULL)
    var observation: Observation? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "authorId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    var author: UserAccount? = null

    @PrePersist
    protected fun onCreate() {
        if (createdAt == null) {
            createdAt = Times.now()
        }
    }
}
