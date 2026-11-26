package com.fermapp.api.persistence.repository;

import com.fermapp.api.persistence.entity.AiConversation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiConversationRepository extends JpaRepository<AiConversation, UUID> {

    Optional<AiConversation> findByIdAndFarmIdAndUserId(UUID id, UUID farmId, UUID userId);

    List<AiConversation> findByFarmIdAndUserIdOrderByUpdatedAtDesc(UUID farmId, UUID userId, Pageable page);
}
