package com.fermapp.api.persistence.repository

import com.fermapp.api.persistence.entity.CropRecommendationRecord
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface CropRecommendationRepository : JpaRepository<CropRecommendationRecord, UUID> {

    @Modifying
    @Query("delete from CropRecommendationRecord r where r.parcelId = :parcelId and r.year = :year")
    fun deleteForYear(@Param("parcelId") parcelId: UUID, @Param("year") year: Int): Int
}
