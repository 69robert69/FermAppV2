package com.fermapp.api.modules.notifications

import com.fasterxml.jackson.databind.ObjectMapper
import com.fermapp.api.domain.model.Role
import com.fermapp.api.integrations.ExpoPushClient
import com.fermapp.api.modules.common.Views.NotificationView
import com.fermapp.api.persistence.entity.Notification
import com.fermapp.api.persistence.repository.FarmMembershipRepository
import com.fermapp.api.persistence.repository.NotificationRepository
import com.fermapp.api.persistence.repository.PushDeviceRepository
import com.fermapp.api.realtime.RealtimeEvent
import com.fermapp.api.realtime.RealtimeHub
import java.util.UUID
import org.springframework.stereotype.Service

/** Saves notifications and sends them live in the app and via push (notifications.service.ts). */
@Service
class NotificationService(
    private val memberships: FarmMembershipRepository,
    private val notifications: NotificationRepository,
    private val devices: PushDeviceRepository,
    private val push: ExpoPushClient,
    private val realtime: RealtimeHub,
    private val mapper: ObjectMapper,
) {

    /**
     * What to send and to whom: the farm members, optionally limited to some users and/or roles.
     *
     * @param userIds null for every member
     * @param roles null for every role
     */
    @JvmRecord
    data class NotificationInput(
        val farmId: UUID,
        val userIds: Collection<UUID>?,
        val roles: Collection<Role>?,
        val title: String?,
        val body: String?,
        val data: Map<String, String>?,
    )

    fun notify(input: NotificationInput) {
        val members = when {
            input.userIds != null && input.roles != null ->
                memberships.findByFarmIdAndUserIdInAndRoleIn(input.farmId, input.userIds, input.roles)
            input.userIds != null -> memberships.findByFarmIdAndUserIdIn(input.farmId, input.userIds)
            input.roles != null -> memberships.findByFarmIdAndRoleIn(input.farmId, input.roles)
            else -> memberships.findByFarmId(input.farmId)
        }
        val recipients = members.map { it.userId }
        if (recipients.isEmpty()) {
            return
        }

        val rows = recipients.map { userId ->
            Notification().apply {
                farmId = input.farmId
                this.userId = userId
                title = input.title!!
                body = input.body!!
                data = input.data?.let { mapper.valueToTree(it) }
            }
        }
        for (row in notifications.saveAll(rows)) {
            realtime.emitToUser(row.userId, RealtimeEvent.NOTIFICATION_NEW, NotificationView.of(row))
        }

        val tokens = devices.findByUserIdIn(recipients).map { it.token }
        val stale = push.send(tokens, input.title, input.body, input.data)
        if (stale.isNotEmpty()) {
            devices.deleteByTokens(stale)
        }
    }
}
