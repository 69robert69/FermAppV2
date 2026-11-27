package com.fermapp.api.modules.auth

import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fasterxml.jackson.annotation.JsonUnwrapped
import com.fermapp.api.common.util.Text
import com.fermapp.api.domain.model.Role
import com.fermapp.api.modules.common.Views.PublicUser
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.util.Locale
import java.util.UUID

/** Requests and responses of the authentication endpoints. */
object AuthDtos {

    /** The email format zod accepted: a dotted domain with a TLD of at least two letters. */
    const val EMAIL_REGEX =
        "^(?!\\.)(?!.*\\.\\.)([A-Za-z0-9_'+\\-.]*)[A-Za-z0-9_+-]@([A-Za-z0-9][A-Za-z0-9\\-]*\\.)+[A-Za-z]{2,}$"
    const val PASSWORD_MESSAGE = "The password must be at least 10 characters long."

    /** `email.trim().toLowerCase()`, with String.trim() semantics and the default locale. */
    internal fun normalizeEmail(email: String?): String? = email?.trim { it <= ' ' }?.lowercase(Locale.getDefault())

    // The order of the Java record, which the OpenAPI document keeps.
    @JsonPropertyOrder("email", "password", "fullName", "farmName", "county")
    class RegisterRequest(
        email: String?,
        @field:NotNull @field:Size(min = 10, message = PASSWORD_MESSAGE) @field:Size(max = 72) val password: String?,
        fullName: String?,
        farmName: String?,
        county: String?,
    ) {
        @field:NotNull @field:Email(regexp = EMAIL_REGEX, message = "Invalid email address")
        val email: String? = normalizeEmail(email)

        @field:NotNull @field:Size(min = 2, max = 120)
        val fullName: String? = Text.trim(fullName)

        @field:NotNull @field:Size(min = 2, max = 120)
        val farmName: String? = Text.trim(farmName)

        @field:Size(max = 60)
        val county: String? = Text.trim(county)
    }

    // The order of the Java record, which the OpenAPI document keeps.
    @JsonPropertyOrder("email", "password")
    class LoginRequest(
        email: String?,
        @field:NotNull @field:Size(min = 1, max = 72) val password: String?,
    ) {
        @field:NotNull @field:Email(regexp = EMAIL_REGEX, message = "Invalid email address")
        val email: String? = normalizeEmail(email)
    }

    class RefreshRequest(@field:NotNull @field:Size(min = 20, max = 200) val refreshToken: String?)

    class SwitchFarmRequest(@field:NotNull val farmId: UUID?)

    class PushTokenRequest(
        @field:NotNull @field:Pattern(regexp = "^Expo(nent)?PushToken\\[.+\\]$", message = "Invalid Expo token.") @field:Size(max = 200)
        val token: String?,
        @field:Pattern(regexp = "ios|android") val platform: String?,
    )

    /** The access token, the rotated refresh token and the access token lifetime in seconds. */
    @JvmRecord
    data class SessionTokens(val accessToken: String, val refreshToken: String, val expiresIn: Int)

    @JvmRecord
    data class FarmRef(val id: UUID?, val name: String?)

    @JvmRecord
    data class FarmWithRole(val id: UUID?, val name: String?, val role: Role?)

    @JvmRecord
    data class RegisterResponse(
        val user: PublicUser,
        val farm: FarmRef,
        val role: Role,
        @get:JsonUnwrapped val session: SessionTokens,
    )

    @JvmRecord
    data class LoginResponse(
        val user: PublicUser,
        val farm: FarmRef,
        val role: Role?,
        val farms: List<FarmWithRole>,
        @get:JsonUnwrapped val session: SessionTokens,
    )

    @JvmRecord
    data class MeResponse(val user: PublicUser, val role: Role?, val farmId: UUID?, val farms: List<FarmWithRole>)

    @JvmRecord
    data class SwitchFarmResponse(val farmId: UUID, val role: Role?, @get:JsonUnwrapped val session: SessionTokens)
}
