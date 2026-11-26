package com.fermapp.api.modules.notifications;

import com.fermapp.api.common.util.Times;
import com.fermapp.api.common.web.ApiResponse;
import com.fermapp.api.modules.common.Views.NotificationView;
import com.fermapp.api.persistence.repository.NotificationRepository;
import com.fermapp.api.security.Access;
import com.fermapp.api.security.AccessGroup;
import com.fermapp.api.security.AuthContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications")
public class NotificationController {

    private final NotificationRepository notifications;

    public NotificationController(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    @Access(AccessGroup.ALL)
    @Operation(summary = "Latest notifications of the user")
    public ResponseEntity<ApiResponse<List<NotificationView>>> list(
            AuthContext auth, @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return ApiResponse.ok(notifications.findByUserIdAndFarmIdOrderByCreatedAtDesc(auth.userId(), auth.farmId(),
                PageRequest.of(0, limit)).stream().map(NotificationView::of).toList());
    }

    @PostMapping("/{id}/read")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Mark the notification as read")
    public ResponseEntity<Void> read(AuthContext auth, @PathVariable UUID id) {
        notifications.markRead(id, auth.userId(), Times.now());
        return ApiResponse.noContent();
    }
}
