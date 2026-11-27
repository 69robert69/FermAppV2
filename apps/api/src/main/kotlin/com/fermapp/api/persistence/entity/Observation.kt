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
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import org.hibernate.annotations.UuidGenerator

/** A field observation written by a team member. */
@Entity
@Table(name = "Observation")
class Observation {

    @Id
    @GeneratedValue
    @UuidGenerator
    lateinit var id: UUID

    @Column(nullable = false)
    lateinit var parcelId: UUID

    @Column(nullable = false)
    lateinit var authorId: UUID

    @Column(nullable = false)
    lateinit var text: String

    var lat: Double? = null

    var lng: Double? = null

    @Column(nullable = false)
    var createdAt: Instant? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parcelId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    var parcel: Parcel? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "authorId", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    var author: UserAccount? = null

    @OneToMany(mappedBy = "observation", fetch = FetchType.LAZY)
    var photos: MutableList<Photo> = ArrayList()

    @PrePersist
    protected fun onCreate() {
        if (createdAt == null) {
            createdAt = Times.now()
        }
    }
}
