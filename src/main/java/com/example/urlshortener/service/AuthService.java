package com.example.urlshortener.service;

import com.example.urlshortener.dto.AuthResponse;
import com.example.urlshortener.dto.LoginRequest;
import com.example.urlshortener.dto.RegisterRequest;
import com.example.urlshortener.entity.User;
import com.example.urlshortener.exception.DuplicateEmailException;
import com.example.urlshortener.exception.InvalidCredentialsException;
import com.example.urlshortener.repository.UserRepository;
import com.example.urlshortener.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
// Spring's @Transactional, not jakarta.transaction.Transactional - see the
// same note in UrlShortenerService. login() below needs readOnly = true
// specifically so it routes to a replica under the postgres-ha profile.
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final OtpService otpService;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (!request.getPassword().equals(request.getConfirmPassword())) {
            throw new IllegalArgumentException("password and confirmPassword do not match");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateEmailException(request.getEmail());
        }

        User user = User.builder()
                .name(request.getName())
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword())) // never store the raw password
                .createdAt(LocalDateTime.now())
                .build();
        userRepository.save(user);

        return new AuthResponse(jwtService.generateToken(user), user.getEmail(), user.getName());
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                // Same exception (and message) whether the email doesn't
                // exist or the password is wrong - not distinguishing them
                // avoids leaking which emails are registered.
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        return new AuthResponse(jwtService.generateToken(user), user.getEmail(), user.getName());
    }

    @Transactional
    public void forgotPassword(String email) {
        String normalizedEmail = email.trim().toLowerCase();
        // Check if user exists
        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new IllegalArgumentException("No account found with this email address."));

        otpService.sendOtp(user.getEmail(), com.example.urlshortener.entity.OtpPurpose.PASSWORD_RESET);
    }

    @Transactional
    public void resetPassword(com.example.urlshortener.dto.ResetPasswordRequest request) {
        if (!request.getNewPassword().equals(request.getConfirmNewPassword())) {
            throw new IllegalArgumentException("newPassword and confirmNewPassword do not match");
        }

        String normalizedEmail = request.getEmail().trim().toLowerCase();
        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new IllegalArgumentException("No account found with this email address."));

        // Verify and mark OTP as used
        otpService.verifyOtp(normalizedEmail, request.getOtp(), com.example.urlshortener.entity.OtpPurpose.PASSWORD_RESET, true);

        // Update password
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
    }
}
