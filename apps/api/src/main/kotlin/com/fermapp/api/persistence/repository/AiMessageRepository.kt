package com.fermapp.api.persistence.repository

import com.fermapp.api.persistence.entity.AiMessage
import java.util.UUID
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface AiMessageRepository : JpaRepository<AiMessage, UUID> {

    fun findByConversationIdOrderByCreatedAtDesc(conversationId: UUID, page: Pageable): List<AiMessage>

    fun findByConversationIdOrderByCreatedAtAsc(conversationId: UUID): List<AiMessage>
}
