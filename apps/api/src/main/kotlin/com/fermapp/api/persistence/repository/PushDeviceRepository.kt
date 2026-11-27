package com.fermapp.api.persistence.repository

import com.fermapp.api.persistence.entity.PushDevice
import java.util.Optional
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

interface PushDeviceRepository : JpaRepository<PushDevice, UUID> {

    fun findByToken(token: String): Optional<PushDevice>

    fun findByUserIdIn(userIds: Collection<UUID>): List<PushDevice>

    @Transactional
    @Modifying
    @Query("delete from PushDevice d where d.token in :tokens")
    fun deleteByTokens(@Param("tokens") tokens: Collection<String>): Int
}
