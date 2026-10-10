package com.example.urlshortener.controller;

import com.example.urlshortener.dto.AuthResponse;
import com.example.urlshortener.dto.LoginRequest;
import com.example.urlshortener.dto.RegisterRequest;
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
@Tag(name = "Auth", description = "Registration and login")
public class AuthController {

    private final AuthService authService;
    private final com.example.urlshortener.service.OtpService otpService;

    @Operation(summary = "Create an account")
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @Operation(summary = "Log in and receive a JWT")
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @Operation(summary = "Request a password reset OTP code via email")
    @PostMapping("/forgot-password")
    public ResponseEntity<java.util.Map<String, String>> forgotPassword(@Valid @RequestBody com.example.urlshortener.dto.ForgotPasswordRequest request) {
        authService.forgotPassword(request.getEmail());
        return ResponseEntity.ok(java.util.Map.of("message", "A 6-digit verification code has been sent to your email."));
    }

    @Operation(summary = "Reset password using verified OTP code")
    @PostMapping("/reset-password")
    public ResponseEntity<java.util.Map<String, String>> resetPassword(@Valid @RequestBody com.example.urlshortener.dto.ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok(java.util.Map.of("message", "Your password has been successfully reset. You can now log in."));
    }

    @Operation(summary = "Send an OTP code to email for validation")
    @PostMapping("/send-otp")
    public ResponseEntity<java.util.Map<String, String>> sendOtp(@Valid @RequestBody com.example.urlshortener.dto.SendOtpRequest request) {
        otpService.sendOtp(request.getEmail(), request.getPurpose());
        return ResponseEntity.ok(java.util.Map.of("message", "Verification code sent to your email."));
    }

    @Operation(summary = "Verify an email OTP code")
    @PostMapping("/verify-otp")
    public ResponseEntity<java.util.Map<String, Object>> verifyOtp(@Valid @RequestBody com.example.urlshortener.dto.VerifyOtpRequest request) {
        boolean valid = otpService.verifyOtp(request.getEmail(), request.getOtp(), request.getPurpose(), false);
        return ResponseEntity.ok(java.util.Map.of("valid", valid, "message", "Code verified successfully."));
    }
}
