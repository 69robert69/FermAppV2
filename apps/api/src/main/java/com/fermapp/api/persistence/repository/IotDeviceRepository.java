package com.fermapp.api.persistence.repository;

import com.fermapp.api.persistence.entity.IotDevice;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface IotDeviceRepository extends JpaRepository<IotDevice, UUID> {

    Optional<IotDevice> findByKeyHash(String keyHash);

    Optional<IotDevice> findByIdAndFarmId(UUID id, UUID farmId);

    List<IotDevice> findByFarmIdOrderByNameAsc(UUID farmId);

    @Transactional
    @Modifying
    @Query("delete from IotDevice d where d.id = :id and d.farmId = :farmId")
    int deleteInFarm(@Param("id") UUID id, @Param("farmId") UUID farmId);

    @Transactional
    @Modifying
    @Query("update IotDevice d set d.lastSeenAt = :now, d.updatedAt = :now where d.id = :id")
    int markSeen(@Param("id") UUID id, @Param("now") Instant now);
}
