package com.fermapp.api.persistence.repository

import com.fermapp.api.persistence.entity.IdempotencyRecord
import java.time.Instant
import java.util.Optional
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

interface IdempotencyRecordRepository : JpaRepository<IdempotencyRecord, UUID> {

    fun findByUserIdAndKey(userId: UUID, key: String): Optional<IdempotencyRecord>

    @Transactional
    @Modifying
    @Query("delete from IdempotencyRecord r where r.createdAt < :before")
    fun deleteOlderThan(@Param("before") before: Instant): Int
}
