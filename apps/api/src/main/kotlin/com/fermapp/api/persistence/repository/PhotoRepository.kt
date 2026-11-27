package com.fermapp.api.persistence.repository

import com.fermapp.api.persistence.entity.Photo
import java.util.Optional
import java.util.UUID
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface PhotoRepository : JpaRepository<Photo, UUID> {

    fun findByParcelIdOrderByCreatedAtDesc(parcelId: UUID, page: Pageable): List<Photo>

    @Query("select ph from Photo ph join ph.parcel p where ph.id = :photoId and ph.parcelId = :parcelId and p.farmId = :farmId")
    fun findInFarm(@Param("farmId") farmId: UUID, @Param("parcelId") parcelId: UUID, @Param("photoId") photoId: UUID): Optional<Photo>
}
