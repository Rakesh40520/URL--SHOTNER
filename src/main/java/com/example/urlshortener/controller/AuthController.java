package com.example.urlshortener.controller;

import com.example.urlshortener.dto.AuthResponse;
import com.example.urlshortener.dto.EmailRequest;
import com.example.urlshortener.dto.LoginRequest;
import com.example.urlshortener.dto.MessageResponse;
import com.example.urlshortener.dto.RegisterRequest;
import com.example.urlshortener.dto.RegisterResponse;
import com.example.urlshortener.dto.ResetPasswordRequest;
import com.example.urlshortener.dto.VerifyEmailRequest;
import com.example.urlshortener.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "Registration, email verification, login and password reset")
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "Create an account and email a 6-digit verification code (does not log in)")
    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @Operation(summary = "Verify an email address with the emailed code; returns a JWT")
    @PostMapping("/verify-email")
    public ResponseEntity<AuthResponse> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        return ResponseEntity.ok(authService.verifyEmail(request.getEmail(), request.getCode()));
    }

    @Operation(summary = "Email a new verification code (only sends if the account exists and is unverified)")
    @PostMapping("/resend-code")
    public ResponseEntity<MessageResponse> resendCode(@Valid @RequestBody EmailRequest request) {
        authService.resendVerification(request.getEmail());
        return ResponseEntity.ok(new MessageResponse("If that account needs verifying, a new code is on its way."));
    }

    @Operation(summary = "Log in and receive a JWT (email must be verified)")
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @Operation(summary = "Email a password-reset code (same answer whether or not the account exists)")
    @PostMapping("/forgot-password")
    public ResponseEntity<MessageResponse> forgotPassword(@Valid @RequestBody EmailRequest request) {
        authService.forgotPassword(request.getEmail());
        return ResponseEntity.ok(new MessageResponse("If that email is registered, we've sent a 6-digit code."));
    }

    @Operation(summary = "Set a new password using the emailed code")
    @PostMapping("/reset-password")
    public ResponseEntity<MessageResponse> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok(new MessageResponse("Password updated. You can log in now."));
    }
}
