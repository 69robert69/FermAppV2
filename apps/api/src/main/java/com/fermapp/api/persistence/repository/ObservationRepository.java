package com.fermapp.api.persistence.repository;

import com.fermapp.api.persistence.entity.Observation;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ObservationRepository extends JpaRepository<Observation, UUID> {

    @Query("select o from Observation o join fetch o.author where o.parcelId = :parcelId order by o.createdAt desc")
    List<Observation> findLatest(@Param("parcelId") UUID parcelId, Pageable page);
}
