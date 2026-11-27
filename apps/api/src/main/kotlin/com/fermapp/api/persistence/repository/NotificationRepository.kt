package com.fermapp.api.persistence.repository

import com.fermapp.api.persistence.entity.Notification
import java.time.Instant
import java.util.UUID
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

interface NotificationRepository : JpaRepository<Notification, UUID> {

    fun findByUserIdAndFarmIdOrderByCreatedAtDesc(userId: UUID, farmId: UUID, page: Pageable): List<Notification>

    @Transactional
    @Modifying
    @Query("update Notification n set n.readAt = :now where n.id = :id and n.userId = :userId and n.readAt is null")
    fun markRead(@Param("id") id: UUID, @Param("userId") userId: UUID, @Param("now") now: Instant): Int
}
