package com.fermapp.api.persistence.repository

import com.fermapp.api.persistence.entity.WeatherAlert
import java.util.UUID
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface WeatherAlertRepository : JpaRepository<WeatherAlert, UUID> {

    fun existsByParcelIdAndDedupeKey(parcelId: UUID, dedupeKey: String): Boolean

    @Query("select a from WeatherAlert a join fetch a.parcel where a.farmId = :farmId order by a.createdAt desc")
    fun findLatest(@Param("farmId") farmId: UUID, page: Pageable): List<WeatherAlert>

    @Query("select a from WeatherAlert a join fetch a.parcel where a.farmId = :farmId and a.parcelId = :parcelId order by a.createdAt desc")
    fun findLatestForParcel(@Param("farmId") farmId: UUID, @Param("parcelId") parcelId: UUID, page: Pageable): List<WeatherAlert>
}
