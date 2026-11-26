package com.fermapp.api.persistence.repository;

import com.fermapp.api.persistence.entity.SoilProfile;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SoilProfileRepository extends JpaRepository<SoilProfile, UUID> {

    Optional<SoilProfile> findByParcelId(UUID parcelId);

    List<SoilProfile> findByParcelIdIn(Collection<UUID> parcelIds);
}
