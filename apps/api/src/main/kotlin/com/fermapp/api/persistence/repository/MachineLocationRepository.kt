package com.fermapp.api.persistence.repository

import com.fermapp.api.persistence.entity.MachineLocation
import java.time.Instant
import java.util.Optional
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

interface MachineLocationRepository : JpaRepository<MachineLocation, Long?> {

    fun findFirstByWorkSessionIdOrderByRecordedAtDesc(workSessionId: UUID): Optional<MachineLocation>

    @Transactional
    @Modifying
    @Query("delete from MachineLocation l where l.recordedAt < :before")
    fun deleteOlderThan(@Param("before") before: Instant): Int
}
