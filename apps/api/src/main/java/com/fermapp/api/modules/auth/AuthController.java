package com.fermapp.api.modules.auth;

import com.fermapp.api.common.web.ApiResponse;
import com.fermapp.api.common.web.RateLimitPolicy;
import com.fermapp.api.common.web.RateLimited;
import com.fermapp.api.modules.auth.AuthDtos.LoginRequest;
import com.fermapp.api.modules.auth.AuthDtos.LoginResponse;
import com.fermapp.api.modules.auth.AuthDtos.MeResponse;
import com.fermapp.api.modules.auth.AuthDtos.PushTokenRequest;
import com.fermapp.api.modules.auth.AuthDtos.RefreshRequest;
import com.fermapp.api.modules.auth.AuthDtos.RegisterRequest;
import com.fermapp.api.modules.auth.AuthDtos.RegisterResponse;
import com.fermapp.api.modules.auth.AuthDtos.SessionTokens;
import com.fermapp.api.modules.auth.AuthDtos.SwitchFarmRequest;
import com.fermapp.api.modules.auth.AuthDtos.SwitchFarmResponse;
import com.fermapp.api.security.Access;
import com.fermapp.api.security.AccessGroup;
import com.fermapp.api.security.AuthContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    @Access(AccessGroup.PUBLIC)
    @RateLimited(RateLimitPolicy.CREDENTIALS)
    @Operation(summary = "Create an account and a new farm")
    public ResponseEntity<ApiResponse<RegisterResponse>> register(@Valid @RequestBody RegisterRequest body) {
        return ApiResponse.created(auth.register(body));
    }

    @PostMapping("/login")
    @Access(AccessGroup.PUBLIC)
    @RateLimited(RateLimitPolicy.CREDENTIALS)
    @Operation(summary = "Sign in with email and password")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest body) {
        return ApiResponse.ok(auth.login(body));
    }

    @PostMapping("/refresh")
    @Access(AccessGroup.PUBLIC)
    @RateLimited(RateLimitPolicy.CREDENTIALS)
    @Operation(summary = "Renew the session and rotate the refresh token")
    public ResponseEntity<ApiResponse<SessionTokens>> refresh(@Valid @RequestBody RefreshRequest body) {
        return ApiResponse.ok(auth.refresh(body.refreshToken()));
    }

    @PostMapping("/logout")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Close the current session")
    public ResponseEntity<Void> logout(AuthContext context, @Valid @RequestBody RefreshRequest body) {
        auth.logout(context, body.refreshToken());
        return ApiResponse.noContent();
    }

    @GetMapping("/me")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Current user profile")
    public ResponseEntity<ApiResponse<MeResponse>> me(AuthContext context) {
        return ApiResponse.ok(auth.me(context));
    }

    @PostMapping("/switch-farm")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Switch the active farm")
    public ResponseEntity<ApiResponse<SwitchFarmResponse>> switchFarm(AuthContext context, @Valid @RequestBody SwitchFarmRequest body) {
        return ApiResponse.ok(auth.switchFarm(context, body.farmId()));
    }

    @PostMapping("/push-token")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Register the device for push notifications")
    public ResponseEntity<Void> pushToken(AuthContext context, @Valid @RequestBody PushTokenRequest body) {
        auth.registerPushToken(context, body);
        return ApiResponse.noContent();
    }
}
