package com.uicodegen.controller;

import com.uicodegen.dto.request.*;
import com.uicodegen.dto.response.*;
import com.uicodegen.security.UserPrincipal;
import com.uicodegen.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "Authentication endpoints")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/signup")
    @Operation(summary = "Register new user")
    public ResponseEntity<LoginResponse> signup(@Valid @RequestBody SignupRequest req) {
        return ResponseEntity.ok(authService.signup(req));
    }

    @PostMapping("/login")
    @Operation(summary = "Login with email + password")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        return ResponseEntity.ok(authService.login(req));
    }

    @PostMapping("/firebase-login")
    @Operation(summary = "Login or register via Firebase ID token")
    public ResponseEntity<LoginResponse> firebaseLogin(@Valid @RequestBody FirebaseLoginRequest req) {
        return ResponseEntity.ok(authService.firebaseLogin(req));
    }

    @PostMapping("/reset-password")
    @Operation(summary = "Change password (bcrypt users)")
    public ResponseEntity<Map<String, String>> resetPassword(
            @AuthenticationPrincipal UserPrincipal user,
            @RequestBody Map<String, String> body) {
        authService.resetPassword(user.getUserId(),
            body.get("current_password"), body.get("new_password"));
        return ResponseEntity.ok(Map.of("message", "Password updated successfully"));
    }

    @DeleteMapping("/account")
    @Operation(summary = "Soft-delete authenticated user account")
    public ResponseEntity<Map<String, String>> deleteAccount(
            @AuthenticationPrincipal UserPrincipal user) {
        authService.softDeleteAccount(user.getUserId());
        return ResponseEntity.ok(Map.of("message", "Account deactivated successfully"));
    }

    @PutMapping("/profile")
    @Operation(summary = "Update display name")
    public ResponseEntity<Map<String, String>> updateProfile(
            @AuthenticationPrincipal UserPrincipal user,
            @RequestBody Map<String, String> body) {
        String updatedName = authService.updateProfile(user.getUserId(), body.get("name"));
        return ResponseEntity.ok(Map.of("message", "Profile updated successfully", "name", updatedName));
    }

    @GetMapping("/me")
    @Operation(summary = "Get current user info")
    public ResponseEntity<UserResponse> getMe(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(authService.getMe(user.getUserId()));
    }
}
