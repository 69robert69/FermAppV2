package com.fermapp.api.persistence.repository;

import com.fermapp.api.persistence.entity.Photo;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PhotoRepository extends JpaRepository<Photo, UUID> {

    List<Photo> findByParcelIdOrderByCreatedAtDesc(UUID parcelId, Pageable page);

    @Query("select ph from Photo ph join ph.parcel p where ph.id = :photoId and ph.parcelId = :parcelId and p.farmId = :farmId")
    Optional<Photo> findInFarm(@Param("farmId") UUID farmId, @Param("parcelId") UUID parcelId, @Param("photoId") UUID photoId);
}
