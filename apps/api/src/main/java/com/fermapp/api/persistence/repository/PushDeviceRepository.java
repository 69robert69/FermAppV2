package com.fermapp.api.persistence.repository;

import com.fermapp.api.persistence.entity.PushDevice;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PushDeviceRepository extends JpaRepository<PushDevice, UUID> {

    Optional<PushDevice> findByToken(String token);

    List<PushDevice> findByUserIdIn(Collection<UUID> userIds);

    @Transactional
    @Modifying
    @Query("delete from PushDevice d where d.token in :tokens")
    int deleteByTokens(@Param("tokens") Collection<String> tokens);
}
