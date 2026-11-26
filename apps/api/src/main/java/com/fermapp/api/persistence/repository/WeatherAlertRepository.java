package com.fermapp.api.persistence.repository;

import com.fermapp.api.persistence.entity.WeatherAlert;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WeatherAlertRepository extends JpaRepository<WeatherAlert, UUID> {

    boolean existsByParcelIdAndDedupeKey(UUID parcelId, String dedupeKey);

    @Query("select a from WeatherAlert a join fetch a.parcel where a.farmId = :farmId order by a.createdAt desc")
    List<WeatherAlert> findLatest(@Param("farmId") UUID farmId, Pageable page);

    @Query("select a from WeatherAlert a join fetch a.parcel where a.farmId = :farmId and a.parcelId = :parcelId order by a.createdAt desc")
    List<WeatherAlert> findLatestForParcel(@Param("farmId") UUID farmId, @Param("parcelId") UUID parcelId, Pageable page);
}
