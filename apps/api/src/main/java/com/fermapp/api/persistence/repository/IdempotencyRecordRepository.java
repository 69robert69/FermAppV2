package com.fermapp.api.persistence.repository;

import com.fermapp.api.persistence.entity.IdempotencyRecord;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {

    Optional<IdempotencyRecord> findByUserIdAndKey(UUID userId, String key);

    @Transactional
    @Modifying
    @Query("delete from IdempotencyRecord r where r.createdAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}
