package com.fermapp.api.modules.auth

import com.fermapp.api.common.error.Errors
import com.fermapp.api.common.util.Times
import com.fermapp.api.domain.model.Role
import com.fermapp.api.modules.auth.AuthDtos.FarmRef
import com.fermapp.api.modules.auth.AuthDtos.FarmWithRole
import com.fermapp.api.modules.auth.AuthDtos.LoginRequest
import com.fermapp.api.modules.auth.AuthDtos.LoginResponse
import com.fermapp.api.modules.auth.AuthDtos.MeResponse
import com.fermapp.api.modules.auth.AuthDtos.PushTokenRequest
import com.fermapp.api.modules.auth.AuthDtos.RegisterRequest
import com.fermapp.api.modules.auth.AuthDtos.RegisterResponse
import com.fermapp.api.modules.auth.AuthDtos.SessionTokens
import com.fermapp.api.modules.auth.AuthDtos.SwitchFarmResponse
import com.fermapp.api.modules.common.AccessGuard
import com.fermapp.api.modules.common.Views.PublicUser
import com.fermapp.api.persistence.entity.Farm
import com.fermapp.api.persistence.entity.FarmMembership
import com.fermapp.api.persistence.entity.PushDevice
import com.fermapp.api.persistence.entity.UserAccount
import com.fermapp.api.persistence.repository.FarmMembershipRepository
import com.fermapp.api.persistence.repository.FarmRepository
import com.fermapp.api.persistence.repository.PushDeviceRepository
import com.fermapp.api.persistence.repository.RefreshTokenRepository
import com.fermapp.api.persistence.repository.UserAccountRepository
import com.fermapp.api.security.AuthContext
import com.fermapp.api.security.PasswordHasher
import com.fermapp.api.security.TokenHashing
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Accounts and sessions (auth.routes.ts). */
@Service
class AuthService(
    private val users: UserAccountRepository,
    private val farms: FarmRepository,
    private val memberships: FarmMembershipRepository,
    private val refreshTokens: RefreshTokenRepository,
    private val pushDevices: PushDeviceRepository,
    private val passwords: PasswordHasher,
    private val sessions: SessionService,
    private val access: AccessGuard,
) {

    @Transactional
    fun register(body: RegisterRequest): RegisterResponse {
        if (users.existsByEmail(body.email)) {
            throw Errors.conflict("An account with this email address already exists.")
        }
        val hash = passwords.hash(body.password)
        val farm = Farm()
        farm.name = body.farmName
        farm.county = body.county
        farms.save(farm)

        val user = UserAccount()
        user.email = body.email
        user.fullName = body.fullName
        user.passwordHash = hash
        users.save(user)

        val membership = FarmMembership()
        membership.farmId = farm.id
        membership.userId = user.id
        membership.role = Role.ADMIN
        memberships.save(membership)

        val session = sessions.issueSession(AuthContext(user.id, farm.id, Role.ADMIN))
        return RegisterResponse(PublicUser.of(user), FarmRef(farm.id, farm.name), Role.ADMIN, session)
    }

    @Transactional
    fun login(body: LoginRequest): LoginResponse {
        val found = users.findByEmail(body.email).orElse(null)
        val valid = passwords.matches(body.password, found?.passwordHash)
        if (found == null || !valid) {
            throw Errors.unauthorized("Incorrect email or password.")
        }
        val list = memberships.findWithFarmByUser(found.id)
        if (list.isEmpty()) {
            throw Errors.forbidden("The account is not associated with any farm.")
        }
        val first = list[0]
        val session = sessions.issueSession(AuthContext(found.id, first.farmId, first.role))
        return LoginResponse(PublicUser.of(found), FarmRef(first.farm.id, first.farm.name), first.role, farmsOf(list), session)
    }

    /**
     * Rotates the refresh token. A token that was already used indicates a possible leak,
     * so its whole family is revoked.
     */
    fun refresh(refreshToken: String): SessionTokens {
        val stored = refreshTokens.findByTokenHash(TokenHashing.sha256(refreshToken)).orElseThrow { Errors.unauthorized("Invalid session.") }
        if (stored.revokedAt != null) {
            refreshTokens.revokeFamily(stored.familyId, Times.now())
            throw Errors.unauthorized("Invalid session.")
        }
        if (stored.expiresAt.isBefore(Times.now())) {
            throw Errors.unauthorized("Your session has expired.")
        }
        if (refreshTokens.revokeIfActive(stored.id, Times.now()) == 0) {
            throw Errors.unauthorized("Invalid session.")
        }
        val membership = memberships.findByFarmIdAndUserId(stored.farmId, stored.userId)
            .orElseThrow { Errors.unauthorized("Access to the farm has been revoked.") }
        return sessions.issueSession(AuthContext(stored.userId, stored.farmId, membership.role), stored.familyId)
    }

    fun logout(auth: AuthContext, refreshToken: String) {
        refreshTokens.findByTokenHash(TokenHashing.sha256(refreshToken))
            .filter { it.userId == auth.userId() }
            .ifPresent { refreshTokens.revokeFamily(it.familyId, Times.now()) }
    }

    @Transactional(readOnly = true)
    fun me(auth: AuthContext): MeResponse {
        val user = users.findById(auth.userId()).orElseThrow { Errors.recordNotFound() }
        return MeResponse(PublicUser.of(user), auth.role(), auth.farmId(), farmsOf(memberships.findWithFarmByUser(user.id)))
    }

    fun switchFarm(auth: AuthContext, farmId: UUID): SwitchFarmResponse {
        val membership = access.requireMember(farmId, auth.userId())
        val session = sessions.issueSession(AuthContext(auth.userId(), farmId, membership.role))
        return SwitchFarmResponse(farmId, membership.role, session)
    }

    @Transactional
    fun registerPushToken(auth: AuthContext, body: PushTokenRequest) {
        val device = pushDevices.findByToken(body.token).orElseGet { PushDevice() }
        device.token = body.token
        device.userId = auth.userId()
        device.platform = body.platform
        pushDevices.save(device)
    }

    private companion object {
        fun farmsOf(list: List<FarmMembership>): List<FarmWithRole> = list.map { FarmWithRole(it.farm.id, it.farm.name, it.role) }
    }
}
