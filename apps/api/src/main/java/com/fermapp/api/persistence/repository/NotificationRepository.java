package com.fermapp.api.persistence.repository;

import com.fermapp.api.persistence.entity.Notification;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findByUserIdAndFarmIdOrderByCreatedAtDesc(UUID userId, UUID farmId, Pageable page);

    @Transactional
    @Modifying
    @Query("update Notification n set n.readAt = :now where n.id = :id and n.userId = :userId and n.readAt is null")
    int markRead(@Param("id") UUID id, @Param("userId") UUID userId, @Param("now") Instant now);
}
