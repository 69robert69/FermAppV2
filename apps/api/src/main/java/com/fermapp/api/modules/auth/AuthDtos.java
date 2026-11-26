package com.fermapp.api.modules.auth;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fermapp.api.common.util.Text;
import com.fermapp.api.domain.model.Role;
import com.fermapp.api.modules.common.Views.PublicUser;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** Requests and responses of the authentication endpoints. */
public final class AuthDtos {

    /** The email format zod accepted: a dotted domain with a TLD of at least two letters. */
    public static final String EMAIL_REGEX =
            "^(?!\\.)(?!.*\\.\\.)([A-Za-z0-9_'+\\-.]*)[A-Za-z0-9_+-]@([A-Za-z0-9][A-Za-z0-9\\-]*\\.)+[A-Za-z]{2,}$";
    public static final String PASSWORD_MESSAGE = "The password must be at least 10 characters long.";

    private AuthDtos() {
    }

    static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    public record RegisterRequest(
            @NotNull @Email(regexp = EMAIL_REGEX, message = "Invalid email address") String email,
            @NotNull @Size(min = 10, message = PASSWORD_MESSAGE) @Size(max = 72) String password,
            @NotNull @Size(min = 2, max = 120) String fullName,
            @NotNull @Size(min = 2, max = 120) String farmName,
            @Size(max = 60) String county) {
        public RegisterRequest {
            email = normalizeEmail(email);
            fullName = Text.trim(fullName);
            farmName = Text.trim(farmName);
            county = Text.trim(county);
        }
    }

    public record LoginRequest(
            @NotNull @Email(regexp = EMAIL_REGEX, message = "Invalid email address") String email,
            @NotNull @Size(min = 1, max = 72) String password) {
        public LoginRequest {
            email = normalizeEmail(email);
        }
    }

    public record RefreshRequest(@NotNull @Size(min = 20, max = 200) String refreshToken) {
    }

    public record SwitchFarmRequest(@NotNull UUID farmId) {
    }

    public record PushTokenRequest(
            @NotNull @Pattern(regexp = "^Expo(nent)?PushToken\\[.+\\]$", message = "Invalid Expo token.") @Size(max = 200) String token,
            @Pattern(regexp = "ios|android") String platform) {
    }

    /** The access token, the rotated refresh token and the access token lifetime in seconds. */
    public record SessionTokens(String accessToken, String refreshToken, int expiresIn) {
    }

    public record FarmRef(UUID id, String name) {
    }

    public record FarmWithRole(UUID id, String name, Role role) {
    }

    public record RegisterResponse(PublicUser user, FarmRef farm, Role role, @JsonUnwrapped SessionTokens session) {
    }

    public record LoginResponse(PublicUser user, FarmRef farm, Role role, List<FarmWithRole> farms,
                                @JsonUnwrapped SessionTokens session) {
    }

    public record MeResponse(PublicUser user, Role role, UUID farmId, List<FarmWithRole> farms) {
    }

    public record SwitchFarmResponse(UUID farmId, Role role, @JsonUnwrapped SessionTokens session) {
    }
}
