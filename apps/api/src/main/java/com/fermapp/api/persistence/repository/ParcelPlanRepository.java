package com.fermapp.api.persistence.repository;

import com.fermapp.api.persistence.entity.ParcelPlan;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ParcelPlanRepository extends JpaRepository<ParcelPlan, UUID> {

    List<ParcelPlan> findByParcelIdOrderByCreatedAtDesc(UUID parcelId, Pageable page);

    /** The first active plan of each parcel for the given year or later, used by the map. */
    @Query("select p from ParcelPlan p where p.parcelId in :parcelIds and p.active = true and p.year >= :year order by p.year asc")
    List<ParcelPlan> findActiveFrom(@Param("parcelIds") java.util.Collection<UUID> parcelIds, @Param("year") int year);

    @Modifying
    @Query("update ParcelPlan p set p.active = false where p.parcelId = :parcelId and p.year = :year and p.active = true")
    int deactivate(@Param("parcelId") UUID parcelId, @Param("year") int year);
}
