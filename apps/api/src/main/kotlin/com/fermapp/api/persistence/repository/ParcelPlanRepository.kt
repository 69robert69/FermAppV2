package com.fermapp.api.persistence.repository

import com.fermapp.api.persistence.entity.ParcelPlan
import java.util.UUID
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ParcelPlanRepository : JpaRepository<ParcelPlan, UUID> {

    fun findByParcelIdOrderByCreatedAtDesc(parcelId: UUID, page: Pageable): List<ParcelPlan>

    /** The first active plan of each parcel for the given year or later, used by the map. */
    @Query("select p from ParcelPlan p where p.parcelId in :parcelIds and p.active = true and p.year >= :year order by p.year asc")
    fun findActiveFrom(@Param("parcelIds") parcelIds: Collection<UUID>, @Param("year") year: Int): List<ParcelPlan>

    @Modifying
    @Query("update ParcelPlan p set p.active = false where p.parcelId = :parcelId and p.year = :year and p.active = true")
    fun deactivate(@Param("parcelId") parcelId: UUID, @Param("year") year: Int): Int
}
