package com.fermapp.api.modules.team

import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fermapp.api.common.error.Errors
import com.fermapp.api.common.util.Text
import com.fermapp.api.common.util.Times
import com.fermapp.api.common.web.ApiResponse
import com.fermapp.api.domain.model.Role
import com.fermapp.api.modules.auth.AuthDtos
import com.fermapp.api.modules.common.AccessGuard
import com.fermapp.api.persistence.entity.FarmMembership
import com.fermapp.api.persistence.entity.UserAccount
import com.fermapp.api.persistence.repository.FarmMembershipRepository
import com.fermapp.api.persistence.repository.RefreshTokenRepository
import com.fermapp.api.persistence.repository.UserAccountRepository
import com.fermapp.api.security.Access
import com.fermapp.api.security.AccessGroup
import com.fermapp.api.security.AuthContext
import com.fermapp.api.security.PasswordHasher
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.util.Locale
import java.util.UUID
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Farm members and their roles (team.routes.ts). The farm must always keep an administrator. */
@RestController
@RequestMapping("/api/v1/team")
@Tag(name = "Team")
class TeamController(
    private val memberships: FarmMembershipRepository,
    private val users: UserAccountRepository,
    private val refreshTokens: RefreshTokenRepository,
    private val passwords: PasswordHasher,
    private val access: AccessGuard,
) {

    @JvmRecord
    data class Member(val userId: UUID?, val fullName: String?, val email: String?, val role: Role?) {
        companion object {
            fun of(membership: FarmMembership): Member {
                val user: UserAccount = membership.user
                return Member(user.id, user.fullName, user.email, membership.role)
            }
        }
    }

    // The order of the Java record, which the OpenAPI document keeps.
    @JsonPropertyOrder("email", "fullName", "role", "password")
    class AddMemberRequest(
        email: String?,
        fullName: String?,
        @field:NotNull val role: Role?,
        @field:Size(min = 10, message = AuthDtos.PASSWORD_MESSAGE) @field:Size(max = 72) val password: String?,
    ) {
        @field:NotNull @field:Email(regexp = AuthDtos.EMAIL_REGEX, message = "Invalid email address")
        val email: String? = email?.trim { it <= ' ' }?.lowercase(Locale.getDefault())

        @field:NotNull @field:Size(min = 2, max = 120)
        val fullName: String? = Text.trim(fullName)
    }

    class ChangeRoleRequest(@field:NotNull val role: Role?)

    private fun assertAnotherAdmin(farmId: UUID, userId: UUID) {
        if (memberships.countByFarmIdAndRoleAndUserIdNot(farmId, Role.ADMIN, userId) == 0L) {
            throw Errors.badRequest("The farm must have at least one administrator.")
        }
    }

    @GetMapping
    @Access(AccessGroup.ALL)
    @Operation(summary = "List farm members")
    @Transactional(readOnly = true)
    fun list(auth: AuthContext): ResponseEntity<ApiResponse<List<Member>>> =
        ApiResponse.ok(memberships.findTeam(auth.farmId()).map { Member.of(it) })

    @PostMapping
    @Access(AccessGroup.ADMIN)
    @Operation(summary = "Add a member. A password is required only for new accounts.")
    @Transactional
    fun add(auth: AuthContext, @Valid @RequestBody body: AddMemberRequest): ResponseEntity<ApiResponse<Member>> {
        var user = users.findByEmail(body.email).orElse(null)
        if (user == null) {
            if (body.password == null) {
                throw Errors.badRequest("A password is required for a new account.")
            }
            user = UserAccount()
            user.email = body.email
            user.fullName = body.fullName
            user.passwordHash = passwords.hash(body.password)
            users.save(user)
        }
        if (memberships.findByFarmIdAndUserId(auth.farmId(), user.id).isPresent) {
            throw Errors.conflict("The user is already part of the farm.")
        }
        val membership = FarmMembership()
        membership.farmId = auth.farmId()
        membership.userId = user.id
        membership.role = body.role
        memberships.save(membership)
        return ApiResponse.created(Member(user.id, user.fullName, user.email, body.role))
    }

    @PatchMapping("/{userId}")
    @Access(AccessGroup.ADMIN)
    @Operation(summary = "Change a member’s role")
    @Transactional
    fun changeRole(
        auth: AuthContext,
        @PathVariable userId: UUID,
        @Valid @RequestBody body: ChangeRoleRequest,
    ): ResponseEntity<ApiResponse<Member>> {
        val current = access.requireMember(auth.farmId(), userId)
        if (current.role == Role.ADMIN && body.role != Role.ADMIN) {
            assertAnotherAdmin(auth.farmId(), userId)
        }
        current.role = body.role
        memberships.save(current)
        return ApiResponse.ok(Member.of(current))
    }

    @DeleteMapping("/{userId}")
    @Access(AccessGroup.ADMIN)
    @Operation(summary = "Remove a member from the farm")
    @Transactional
    fun remove(auth: AuthContext, @PathVariable userId: UUID): ResponseEntity<Void> {
        val current = access.requireMember(auth.farmId(), userId)
        if (current.role == Role.ADMIN) {
            assertAnotherAdmin(auth.farmId(), userId)
        }
        memberships.delete(current)
        refreshTokens.revokeForUserAndFarm(userId, auth.farmId(), Times.now())
        return ApiResponse.noContent()
    }
}
