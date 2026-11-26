package com.fermapp.api.modules.auth;

import com.fermapp.api.common.error.Errors;
import com.fermapp.api.common.util.Times;
import com.fermapp.api.domain.model.Role;
import com.fermapp.api.modules.auth.AuthDtos.FarmRef;
import com.fermapp.api.modules.auth.AuthDtos.FarmWithRole;
import com.fermapp.api.modules.auth.AuthDtos.LoginRequest;
import com.fermapp.api.modules.auth.AuthDtos.LoginResponse;
import com.fermapp.api.modules.auth.AuthDtos.MeResponse;
import com.fermapp.api.modules.auth.AuthDtos.PushTokenRequest;
import com.fermapp.api.modules.auth.AuthDtos.RegisterRequest;
import com.fermapp.api.modules.auth.AuthDtos.RegisterResponse;
import com.fermapp.api.modules.auth.AuthDtos.SessionTokens;
import com.fermapp.api.modules.auth.AuthDtos.SwitchFarmResponse;
import com.fermapp.api.modules.common.AccessGuard;
import com.fermapp.api.modules.common.Views.PublicUser;
import com.fermapp.api.persistence.entity.Farm;
import com.fermapp.api.persistence.entity.FarmMembership;
import com.fermapp.api.persistence.entity.PushDevice;
import com.fermapp.api.persistence.entity.RefreshToken;
import com.fermapp.api.persistence.entity.UserAccount;
import com.fermapp.api.persistence.repository.FarmMembershipRepository;
import com.fermapp.api.persistence.repository.FarmRepository;
import com.fermapp.api.persistence.repository.PushDeviceRepository;
import com.fermapp.api.persistence.repository.RefreshTokenRepository;
import com.fermapp.api.persistence.repository.UserAccountRepository;
import com.fermapp.api.security.AuthContext;
import com.fermapp.api.security.PasswordHasher;
import com.fermapp.api.security.TokenHashing;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Accounts and sessions (auth.routes.ts). */
@Service
public class AuthService {

    private final UserAccountRepository users;
    private final FarmRepository farms;
    private final FarmMembershipRepository memberships;
    private final RefreshTokenRepository refreshTokens;
    private final PushDeviceRepository pushDevices;
    private final PasswordHasher passwords;
    private final SessionService sessions;
    private final AccessGuard access;

    public AuthService(UserAccountRepository users, FarmRepository farms, FarmMembershipRepository memberships,
                       RefreshTokenRepository refreshTokens, PushDeviceRepository pushDevices, PasswordHasher passwords,
                       SessionService sessions, AccessGuard access) {
        this.users = users;
        this.farms = farms;
        this.memberships = memberships;
        this.refreshTokens = refreshTokens;
        this.pushDevices = pushDevices;
        this.passwords = passwords;
        this.sessions = sessions;
        this.access = access;
    }

    @Transactional
    public RegisterResponse register(RegisterRequest body) {
        if (users.existsByEmail(body.email())) {
            throw Errors.conflict("An account with this email address already exists.");
        }
        String hash = passwords.hash(body.password());
        Farm farm = new Farm();
        farm.setName(body.farmName());
        farm.setCounty(body.county());
        farms.save(farm);

        UserAccount user = new UserAccount();
        user.setEmail(body.email());
        user.setFullName(body.fullName());
        user.setPasswordHash(hash);
        users.save(user);

        FarmMembership membership = new FarmMembership();
        membership.setFarmId(farm.getId());
        membership.setUserId(user.getId());
        membership.setRole(Role.ADMIN);
        memberships.save(membership);

        SessionTokens session = sessions.issueSession(new AuthContext(user.getId(), farm.getId(), Role.ADMIN));
        return new RegisterResponse(PublicUser.of(user), new FarmRef(farm.getId(), farm.getName()), Role.ADMIN, session);
    }

    @Transactional
    public LoginResponse login(LoginRequest body) {
        Optional<UserAccount> found = users.findByEmail(body.email());
        boolean valid = passwords.matches(body.password(), found.map(UserAccount::getPasswordHash).orElse(null));
        if (found.isEmpty() || !valid) {
            throw Errors.unauthorized("Incorrect email or password.");
        }
        UserAccount user = found.get();
        List<FarmMembership> list = memberships.findWithFarmByUser(user.getId());
        if (list.isEmpty()) {
            throw Errors.forbidden("The account is not associated with any farm.");
        }
        FarmMembership first = list.get(0);
        SessionTokens session = sessions.issueSession(new AuthContext(user.getId(), first.getFarmId(), first.getRole()));
        return new LoginResponse(PublicUser.of(user), new FarmRef(first.getFarm().getId(), first.getFarm().getName()),
                first.getRole(), farmsOf(list), session);
    }

    private static List<FarmWithRole> farmsOf(List<FarmMembership> list) {
        return list.stream().map(m -> new FarmWithRole(m.getFarm().getId(), m.getFarm().getName(), m.getRole())).toList();
    }

    /**
     * Rotates the refresh token. A token that was already used indicates a possible leak,
     * so its whole family is revoked.
     */
    public SessionTokens refresh(String refreshToken) {
        RefreshToken stored = refreshTokens.findByTokenHash(TokenHashing.sha256(refreshToken))
                .orElseThrow(() -> Errors.unauthorized("Invalid session."));
        if (stored.getRevokedAt() != null) {
            refreshTokens.revokeFamily(stored.getFamilyId(), Times.now());
            throw Errors.unauthorized("Invalid session.");
        }
        if (stored.getExpiresAt().isBefore(Times.now())) {
            throw Errors.unauthorized("Your session has expired.");
        }
        if (refreshTokens.revokeIfActive(stored.getId(), Times.now()) == 0) {
            throw Errors.unauthorized("Invalid session.");
        }
        FarmMembership membership = memberships.findByFarmIdAndUserId(stored.getFarmId(), stored.getUserId())
                .orElseThrow(() -> Errors.unauthorized("Access to the farm has been revoked."));
        return sessions.issueSession(new AuthContext(stored.getUserId(), stored.getFarmId(), membership.getRole()),
                stored.getFamilyId());
    }

    public void logout(AuthContext auth, String refreshToken) {
        refreshTokens.findByTokenHash(TokenHashing.sha256(refreshToken))
                .filter(stored -> stored.getUserId().equals(auth.userId()))
                .ifPresent(stored -> refreshTokens.revokeFamily(stored.getFamilyId(), Times.now()));
    }

    @Transactional(readOnly = true)
    public MeResponse me(AuthContext auth) {
        UserAccount user = users.findById(auth.userId()).orElseThrow(Errors::recordNotFound);
        return new MeResponse(PublicUser.of(user), auth.role(), auth.farmId(), farmsOf(memberships.findWithFarmByUser(user.getId())));
    }

    public SwitchFarmResponse switchFarm(AuthContext auth, java.util.UUID farmId) {
        FarmMembership membership = access.requireMember(farmId, auth.userId());
        SessionTokens session = sessions.issueSession(new AuthContext(auth.userId(), farmId, membership.getRole()));
        return new SwitchFarmResponse(farmId, membership.getRole(), session);
    }

    @Transactional
    public void registerPushToken(AuthContext auth, PushTokenRequest body) {
        PushDevice device = pushDevices.findByToken(body.token()).orElseGet(PushDevice::new);
        device.setToken(body.token());
        device.setUserId(auth.userId());
        device.setPlatform(body.platform());
        pushDevices.save(device);
    }
}
