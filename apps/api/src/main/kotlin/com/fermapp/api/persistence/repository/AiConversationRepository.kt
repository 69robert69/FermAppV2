package com.fermapp.api.persistence.repository

import com.fermapp.api.persistence.entity.AiConversation
import java.util.Optional
import java.util.UUID
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface AiConversationRepository : JpaRepository<AiConversation, UUID> {

    fun findByIdAndFarmIdAndUserId(id: UUID, farmId: UUID, userId: UUID): Optional<AiConversation>

    fun findByFarmIdAndUserIdOrderByUpdatedAtDesc(farmId: UUID, userId: UUID, page: Pageable): List<AiConversation>
}
