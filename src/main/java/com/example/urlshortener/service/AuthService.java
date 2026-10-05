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
}
