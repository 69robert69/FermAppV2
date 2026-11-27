package com.fermapp.api.persistence.repository

import com.fermapp.api.persistence.entity.IotDevice
import java.time.Instant
import java.util.Optional
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

interface IotDeviceRepository : JpaRepository<IotDevice, UUID> {

    fun findByKeyHash(keyHash: String): Optional<IotDevice>

    fun findByIdAndFarmId(id: UUID, farmId: UUID): Optional<IotDevice>

    fun findByFarmIdOrderByNameAsc(farmId: UUID): List<IotDevice>

    @Transactional
    @Modifying
    @Query("delete from IotDevice d where d.id = :id and d.farmId = :farmId")
    fun deleteInFarm(@Param("id") id: UUID, @Param("farmId") farmId: UUID): Int

    @Transactional
    @Modifying
    @Query("update IotDevice d set d.lastSeenAt = :now, d.updatedAt = :now where d.id = :id")
    fun markSeen(@Param("id") id: UUID, @Param("now") now: Instant): Int
}
