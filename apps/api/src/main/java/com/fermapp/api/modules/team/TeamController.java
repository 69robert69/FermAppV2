package com.fermapp.api.modules.team;

import com.fermapp.api.common.error.Errors;
import com.fermapp.api.common.util.Text;
import com.fermapp.api.common.util.Times;
import com.fermapp.api.common.web.ApiResponse;
import com.fermapp.api.domain.model.Role;
import com.fermapp.api.modules.auth.AuthDtos;
import com.fermapp.api.modules.common.AccessGuard;
import com.fermapp.api.persistence.entity.FarmMembership;
import com.fermapp.api.persistence.entity.UserAccount;
import com.fermapp.api.persistence.repository.FarmMembershipRepository;
import com.fermapp.api.persistence.repository.RefreshTokenRepository;
import com.fermapp.api.persistence.repository.UserAccountRepository;
import com.fermapp.api.security.Access;
import com.fermapp.api.security.AccessGroup;
import com.fermapp.api.security.AuthContext;
import com.fermapp.api.security.PasswordHasher;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Farm members and their roles (team.routes.ts). The farm must always keep an administrator. */
@RestController
@RequestMapping("/api/v1/team")
@Tag(name = "Team")
public class TeamController {

    public record Member(UUID userId, String fullName, String email, Role role) {
        static Member of(FarmMembership membership) {
            UserAccount user = membership.getUser();
            return new Member(user.getId(), user.getFullName(), user.getEmail(), membership.getRole());
        }
    }

    public record AddMemberRequest(
            @NotNull @Email(regexp = AuthDtos.EMAIL_REGEX, message = "Invalid email address") String email,
            @NotNull @Size(min = 2, max = 120) String fullName,
            @NotNull Role role,
            @Size(min = 10, message = AuthDtos.PASSWORD_MESSAGE) @Size(max = 72) String password) {
        public AddMemberRequest {
            email = email == null ? null : email.trim().toLowerCase();
            fullName = Text.trim(fullName);
        }
    }

    public record ChangeRoleRequest(@NotNull Role role) {
    }

    private final FarmMembershipRepository memberships;
    private final UserAccountRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordHasher passwords;
    private final AccessGuard access;

    public TeamController(FarmMembershipRepository memberships, UserAccountRepository users,
                          RefreshTokenRepository refreshTokens, PasswordHasher passwords, AccessGuard access) {
        this.memberships = memberships;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwords = passwords;
        this.access = access;
    }

    private void assertAnotherAdmin(UUID farmId, UUID userId) {
        if (memberships.countByFarmIdAndRoleAndUserIdNot(farmId, Role.ADMIN, userId) == 0) {
            throw Errors.badRequest("The farm must have at least one administrator.");
        }
    }

    @GetMapping
    @Access(AccessGroup.ALL)
    @Operation(summary = "List farm members")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<List<Member>>> list(AuthContext auth) {
        return ApiResponse.ok(memberships.findTeam(auth.farmId()).stream().map(Member::of).toList());
    }

    @PostMapping
    @Access(AccessGroup.ADMIN)
    @Operation(summary = "Add a member. A password is required only for new accounts.")
    @Transactional
    public ResponseEntity<ApiResponse<Member>> add(AuthContext auth, @Valid @RequestBody AddMemberRequest body) {
        UserAccount user = users.findByEmail(body.email()).orElse(null);
        if (user == null) {
            if (body.password() == null) {
                throw Errors.badRequest("A password is required for a new account.");
            }
            user = new UserAccount();
            user.setEmail(body.email());
            user.setFullName(body.fullName());
            user.setPasswordHash(passwords.hash(body.password()));
            users.save(user);
        }
        if (memberships.findByFarmIdAndUserId(auth.farmId(), user.getId()).isPresent()) {
            throw Errors.conflict("The user is already part of the farm.");
        }
        FarmMembership membership = new FarmMembership();
        membership.setFarmId(auth.farmId());
        membership.setUserId(user.getId());
        membership.setRole(body.role());
        memberships.save(membership);
        return ApiResponse.created(new Member(user.getId(), user.getFullName(), user.getEmail(), body.role()));
    }

    @PatchMapping("/{userId}")
    @Access(AccessGroup.ADMIN)
    @Operation(summary = "Change a member’s role")
    @Transactional
    public ResponseEntity<ApiResponse<Member>> changeRole(AuthContext auth, @PathVariable UUID userId,
                                                          @Valid @RequestBody ChangeRoleRequest body) {
        FarmMembership current = access.requireMember(auth.farmId(), userId);
        if (current.getRole() == Role.ADMIN && body.role() != Role.ADMIN) {
            assertAnotherAdmin(auth.farmId(), userId);
        }
        current.setRole(body.role());
        memberships.save(current);
        return ApiResponse.ok(Member.of(current));
    }

    @DeleteMapping("/{userId}")
    @Access(AccessGroup.ADMIN)
    @Operation(summary = "Remove a member from the farm")
    @Transactional
    public ResponseEntity<Void> remove(AuthContext auth, @PathVariable UUID userId) {
        FarmMembership current = access.requireMember(auth.farmId(), userId);
        if (current.getRole() == Role.ADMIN) {
            assertAnotherAdmin(auth.farmId(), userId);
        }
        memberships.delete(current);
        refreshTokens.revokeForUserAndFarm(userId, auth.farmId(), Times.now());
        return ApiResponse.noContent();
    }
}
