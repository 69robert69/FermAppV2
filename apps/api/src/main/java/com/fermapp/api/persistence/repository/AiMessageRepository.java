package com.fermapp.api.persistence.repository;

import com.fermapp.api.persistence.entity.AiMessage;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiMessageRepository extends JpaRepository<AiMessage, UUID> {

    List<AiMessage> findByConversationIdOrderByCreatedAtDesc(UUID conversationId, Pageable page);

    List<AiMessage> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);
}
