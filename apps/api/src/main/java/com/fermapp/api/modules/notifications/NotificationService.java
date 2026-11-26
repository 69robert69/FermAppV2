package com.fermapp.api.modules.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fermapp.api.domain.model.Role;
import com.fermapp.api.integrations.ExpoPushClient;
import com.fermapp.api.modules.common.Views.NotificationView;
import com.fermapp.api.persistence.entity.FarmMembership;
import com.fermapp.api.persistence.entity.Notification;
import com.fermapp.api.persistence.entity.PushDevice;
import com.fermapp.api.persistence.repository.FarmMembershipRepository;
import com.fermapp.api.persistence.repository.NotificationRepository;
import com.fermapp.api.persistence.repository.PushDeviceRepository;
import com.fermapp.api.realtime.RealtimeEvent;
import com.fermapp.api.realtime.RealtimeHub;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Saves notifications and sends them live in the app and via push (notifications.service.ts). */
@Service
public class NotificationService {

    /**
     * What to send and to whom: the farm members, optionally limited to some users and/or roles.
     *
     * @param userIds null for every member
     * @param roles null for every role
     */
    public record NotificationInput(UUID farmId, Collection<UUID> userIds, Collection<Role> roles, String title, String body,
                                    Map<String, String> data) {
    }

    private final FarmMembershipRepository memberships;
    private final NotificationRepository notifications;
    private final PushDeviceRepository devices;
    private final ExpoPushClient push;
    private final RealtimeHub realtime;
    private final ObjectMapper mapper;

    public NotificationService(FarmMembershipRepository memberships, NotificationRepository notifications,
                               PushDeviceRepository devices, ExpoPushClient push, RealtimeHub realtime, ObjectMapper mapper) {
        this.memberships = memberships;
        this.notifications = notifications;
        this.devices = devices;
        this.push = push;
        this.realtime = realtime;
        this.mapper = mapper;
    }

    public void notify(NotificationInput input) {
        List<FarmMembership> members;
        if (input.userIds() != null && input.roles() != null) {
            members = memberships.findByFarmIdAndUserIdInAndRoleIn(input.farmId(), input.userIds(), input.roles());
        } else if (input.userIds() != null) {
            members = memberships.findByFarmIdAndUserIdIn(input.farmId(), input.userIds());
        } else if (input.roles() != null) {
            members = memberships.findByFarmIdAndRoleIn(input.farmId(), input.roles());
        } else {
            members = memberships.findByFarmId(input.farmId());
        }
        List<UUID> recipients = members.stream().map(FarmMembership::getUserId).toList();
        if (recipients.isEmpty()) {
            return;
        }

        List<Notification> rows = new ArrayList<>();
        for (UUID userId : recipients) {
            Notification notification = new Notification();
            notification.setFarmId(input.farmId());
            notification.setUserId(userId);
            notification.setTitle(input.title());
            notification.setBody(input.body());
            notification.setData(input.data() == null ? null : mapper.valueToTree(input.data()));
            rows.add(notification);
        }
        for (Notification row : notifications.saveAll(rows)) {
            realtime.emitToUser(row.getUserId(), RealtimeEvent.NOTIFICATION_NEW, NotificationView.of(row));
        }

        List<String> tokens = devices.findByUserIdIn(recipients).stream().map(PushDevice::getToken).toList();
        List<String> stale = push.send(tokens, input.title(), input.body(), input.data());
        if (!stale.isEmpty()) {
            devices.deleteByTokens(stale);
        }
    }
}
