package com.fermapp.api.persistence.repository

import com.fermapp.api.persistence.entity.Observation
import java.util.UUID
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ObservationRepository : JpaRepository<Observation, UUID> {

    @Query("select o from Observation o join fetch o.author where o.parcelId = :parcelId order by o.createdAt desc")
    fun findLatest(@Param("parcelId") parcelId: UUID, page: Pageable): List<Observation>
}
