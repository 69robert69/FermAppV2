package com.fermapp.api.modules.auth

import com.fermapp.api.common.web.ApiResponse
import com.fermapp.api.common.web.RateLimitPolicy
import com.fermapp.api.common.web.RateLimited
import com.fermapp.api.modules.auth.AuthDtos.LoginRequest
import com.fermapp.api.modules.auth.AuthDtos.LoginResponse
import com.fermapp.api.modules.auth.AuthDtos.MeResponse
import com.fermapp.api.modules.auth.AuthDtos.PushTokenRequest
import com.fermapp.api.modules.auth.AuthDtos.RefreshRequest
import com.fermapp.api.modules.auth.AuthDtos.RegisterRequest
import com.fermapp.api.modules.auth.AuthDtos.RegisterResponse
import com.fermapp.api.modules.auth.AuthDtos.SessionTokens
import com.fermapp.api.modules.auth.AuthDtos.SwitchFarmRequest
import com.fermapp.api.modules.auth.AuthDtos.SwitchFarmResponse
import com.fermapp.api.security.Access
import com.fermapp.api.security.AccessGroup
import com.fermapp.api.security.AuthContext
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication")
class AuthController(private val auth: AuthService) {

    @PostMapping("/register")
    @Access(AccessGroup.PUBLIC)
    @RateLimited(RateLimitPolicy.CREDENTIALS)
    @Operation(summary = "Create an account and a new farm")
    fun register(@Valid @RequestBody body: RegisterRequest): ResponseEntity<ApiResponse<RegisterResponse>> =
        ApiResponse.created(auth.register(body))

    @PostMapping("/login")
    @Access(AccessGroup.PUBLIC)
    @RateLimited(RateLimitPolicy.CREDENTIALS)
    @Operation(summary = "Sign in with email and password")
    fun login(@Valid @RequestBody body: LoginRequest): ResponseEntity<ApiResponse<LoginResponse>> = ApiResponse.ok(auth.login(body))

    @PostMapping("/refresh")
    @Access(AccessGroup.PUBLIC)
    @RateLimited(RateLimitPolicy.CREDENTIALS)
    @Operation(summary = "Renew the session and rotate the refresh token")
    fun refresh(@Valid @RequestBody body: RefreshRequest): ResponseEntity<ApiResponse<SessionTokens>> =
        ApiResponse.ok(auth.refresh(body.refreshToken!!))

    @PostMapping("/logout")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Close the current session")
    fun logout(context: AuthContext, @Valid @RequestBody body: RefreshRequest): ResponseEntity<Void> {
        auth.logout(context, body.refreshToken!!)
        return ApiResponse.noContent()
    }

    @GetMapping("/me")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Current user profile")
    fun me(context: AuthContext): ResponseEntity<ApiResponse<MeResponse>> = ApiResponse.ok(auth.me(context))

    @PostMapping("/switch-farm")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Switch the active farm")
    fun switchFarm(context: AuthContext, @Valid @RequestBody body: SwitchFarmRequest): ResponseEntity<ApiResponse<SwitchFarmResponse>> =
        ApiResponse.ok(auth.switchFarm(context, body.farmId!!))

    @PostMapping("/push-token")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Register the device for push notifications")
    fun pushToken(context: AuthContext, @Valid @RequestBody body: PushTokenRequest): ResponseEntity<Void> {
        auth.registerPushToken(context, body)
        return ApiResponse.noContent()
    }
}
