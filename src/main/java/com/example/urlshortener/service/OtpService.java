package com.example.urlshortener.service;

import com.example.urlshortener.entity.OtpPurpose;
import com.example.urlshortener.entity.OtpToken;
import com.example.urlshortener.exception.InvalidCredentialsException;
import com.example.urlshortener.repository.OtpTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class OtpService {

    private final OtpTokenRepository otpTokenRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.mail.otp-validity-minutes:10}")
    private int validityMinutes;

    @Value("${app.mail.cooldown-seconds:60}")
    private int cooldownSeconds;

    @Transactional
    public void sendOtp(String email, OtpPurpose purpose) {
        String normalizedEmail = email.trim().toLowerCase();
        LocalDateTime now = LocalDateTime.now();

        // Enforce cooldown so users cannot spam requests
        otpTokenRepository.findTopByEmailAndPurposeAndUsedFalseOrderByCreatedAtDesc(normalizedEmail, purpose)
                .ifPresent(existing -> {
                    if (existing.getCreatedAt().plusSeconds(cooldownSeconds).isAfter(now)) {
                        long secondsLeft = java.time.Duration.between(now, existing.getCreatedAt().plusSeconds(cooldownSeconds)).getSeconds();
                        throw new IllegalArgumentException("Please wait " + Math.max(1, secondsLeft) + " seconds before requesting a new code.");
                    }
                });

        // Generate 6-digit numeric OTP (e.g., 100000 - 999999)
        String rawOtp = String.format("%06d", secureRandom.nextInt(900000) + 100000);

        OtpToken token = OtpToken.builder()
                .email(normalizedEmail)
                .otpHash(passwordEncoder.encode(rawOtp))
                .purpose(purpose)
                .expiresAt(now.plusMinutes(validityMinutes))
                .createdAt(now)
                .attemptCount(0)
                .used(false)
                .build();

        otpTokenRepository.save(token);

        emailService.sendOtpEmail(normalizedEmail, rawOtp, purpose, validityMinutes);
    }

    @Transactional
    public boolean verifyOtp(String email, String rawOtp, OtpPurpose purpose, boolean markAsUsed) {
        String normalizedEmail = email.trim().toLowerCase();

        OtpToken token = otpTokenRepository
                .findTopByEmailAndPurposeAndUsedFalseOrderByCreatedAtDesc(normalizedEmail, purpose)
                .orElseThrow(() -> new IllegalArgumentException("No active verification code found for this email. Please request a new code."));

        if (token.isExpired()) {
            throw new IllegalArgumentException("Verification code has expired. Please request a new code.");
        }

        if (token.isMaxAttemptsExceeded()) {
            token.setUsed(true);
            otpTokenRepository.save(token);
            throw new IllegalArgumentException("Too many incorrect attempts. Please request a new code.");
        }

        token.setAttemptCount(token.getAttemptCount() + 1);

        if (!passwordEncoder.matches(rawOtp.trim(), token.getOtpHash())) {
            otpTokenRepository.save(token);
            int remaining = Math.max(0, 5 - token.getAttemptCount());
            throw new IllegalArgumentException("Invalid verification code (" + remaining + " attempts remaining).");
        }

        if (markAsUsed) {
            token.setUsed(true);
        }
        otpTokenRepository.save(token);
        return true;
    }
}
