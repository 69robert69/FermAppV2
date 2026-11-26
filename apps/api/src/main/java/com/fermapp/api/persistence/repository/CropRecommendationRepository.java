package com.fermapp.api.persistence.repository;

import com.fermapp.api.persistence.entity.CropRecommendationRecord;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CropRecommendationRepository extends JpaRepository<CropRecommendationRecord, UUID> {

    @Modifying
    @Query("delete from CropRecommendationRecord r where r.parcelId = :parcelId and r.year = :year")
    int deleteForYear(@Param("parcelId") UUID parcelId, @Param("year") int year);
}
