package com.example.urlshortener.service;

import com.example.urlshortener.dto.AuthResponse;
import com.example.urlshortener.dto.LoginRequest;
import com.example.urlshortener.dto.RegisterRequest;
import com.example.urlshortener.dto.RegisterResponse;
import com.example.urlshortener.dto.ResetPasswordRequest;
import com.example.urlshortener.entity.EmailCodePurpose;
import com.example.urlshortener.entity.User;
import com.example.urlshortener.exception.DuplicateEmailException;
import com.example.urlshortener.exception.EmailDeliveryException;
import com.example.urlshortener.exception.EmailNotVerifiedException;
import com.example.urlshortener.exception.InvalidCodeException;
import com.example.urlshortener.exception.InvalidCredentialsException;
import com.example.urlshortener.repository.UserRepository;
import com.example.urlshortener.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
// Spring's @Transactional, not jakarta.transaction.Transactional - see the
// same note in UrlShortenerService. login() below needs readOnly = true
// specifically so it routes to a replica under the postgres-ha profile.
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final EmailCodeService emailCodeService;

    /**
     * Creates the account (unverified) and emails a 6-digit code. Does NOT log the
     * user in - they must verify first. Re-registering an address that never
     * finished verifying simply updates it and sends a fresh code, so nobody can
     * squat on someone else's address.
     */
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        if (!request.getPassword().equals(request.getConfirmPassword())) {
            throw new IllegalArgumentException("password and confirmPassword do not match");
        }
        String email = normalize(request.getEmail());

        Optional<User> existing = findUser(request.getEmail());
        User user;
        if (existing.isPresent()) {
            if (existing.get().isEmailVerifiedEffective()) {
                throw new DuplicateEmailException(email);
            }
            user = existing.get();
            user.setName(request.getName());
            user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        } else {
            user = User.builder()
                    .name(request.getName())
                    .email(email)
                    .passwordHash(passwordEncoder.encode(request.getPassword())) // never store the raw password
                    .createdAt(LocalDateTime.now())
                    .emailVerified(false)
                    .build();
        }
        userRepository.save(user);

        emailCodeService.issue(user.getEmail(), EmailCodePurpose.VERIFY_EMAIL, user.getName());
        return new RegisterResponse("We sent a 6-digit code to " + user.getEmail() + ".", user.getEmail(), true);
    }

    /** Checks the emailed code, marks the address verified, and logs the user in. */
    @Transactional(noRollbackFor = InvalidCodeException.class)
    public AuthResponse verifyEmail(String email, String code) {
        User user = findUser(email).orElseThrow(InvalidCodeException::expiredOrUnknown);
        emailCodeService.verify(user.getEmail(), EmailCodePurpose.VERIFY_EMAIL, code);

        user.setEmailVerified(true);
        userRepository.save(user);
        return new AuthResponse(jwtService.generateToken(user), user.getEmail(), user.getName());
    }

    /** Sends a new verification code if (and only if) the account exists and is unverified. */
    @Transactional
    public void resendVerification(String email) {
        findUser(email)
                .filter(u -> !u.isEmailVerifiedEffective())
                .ifPresent(u -> emailCodeService.issue(u.getEmail(), EmailCodePurpose.VERIFY_EMAIL, u.getName()));
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = findUser(request.getEmail())
                // Same exception (and message) whether the email doesn't
                // exist or the password is wrong - not distinguishing them
                // avoids leaking which emails are registered.
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        // Checked only AFTER the password, so this can't be used to discover which
        // addresses are registered. (Read-only transaction: the page that handles this
        // error asks for a code through resendVerification instead.)
        if (!user.isEmailVerifiedEffective()) {
            throw new EmailNotVerifiedException(user.getEmail());
        }

        return new AuthResponse(jwtService.generateToken(user), user.getEmail(), user.getName());
    }

    /**
     * Starts a password reset. The caller always gets the same answer whether or not
     * the address is registered, so this can't be used to find out who has an account.
     */
    @Transactional
    public void forgotPassword(String email) {
        findUser(email).ifPresent(u -> {
            try {
                emailCodeService.issue(u.getEmail(), EmailCodePurpose.RESET_PASSWORD, u.getName());
            } catch (EmailDeliveryException ex) {
                // Don't tell the caller (that would reveal the account exists); the
                // failure is already logged with the provider's reason.
                log.error("Password reset email could not be delivered for {}", u.getEmail());
            }
        });
    }

    /**
     * Sets a new password after the emailed code checks out. Proving control of the
     * inbox also proves the address, so it marks the account verified too.
     */
    @Transactional(noRollbackFor = InvalidCodeException.class)
    public void resetPassword(ResetPasswordRequest request) {
        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new IllegalArgumentException("newPassword and confirmPassword do not match");
        }
        User user = findUser(request.getEmail()).orElseThrow(InvalidCodeException::expiredOrUnknown);
        emailCodeService.verify(user.getEmail(), EmailCodePurpose.RESET_PASSWORD, request.getCode());

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setEmailVerified(true);
        userRepository.save(user);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    // Looks the address up as typed first (accounts created before emails were
    // lower-cased may have capital letters), then lower-cased.
    private Optional<User> findUser(String rawEmail) {
        String typed = rawEmail == null ? "" : rawEmail.trim();
        String lower = typed.toLowerCase();
        Optional<User> exact = userRepository.findByEmail(typed);
        if (exact.isPresent() || typed.equals(lower)) return exact;
        return userRepository.findByEmail(lower);
    }
}
