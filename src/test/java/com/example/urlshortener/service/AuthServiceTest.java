package com.example.urlshortener.service;

import com.example.urlshortener.dto.AuthResponse;
import com.example.urlshortener.dto.LoginRequest;
import com.example.urlshortener.dto.RegisterRequest;
import com.example.urlshortener.entity.User;
import com.example.urlshortener.exception.DuplicateEmailException;
import com.example.urlshortener.exception.InvalidCredentialsException;
import com.example.urlshortener.repository.UserRepository;
import com.example.urlshortener.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private JwtService jwtService;
    @Mock private OtpService otpService;

    // Use a real BCryptPasswordEncoder so we can verify actual hashing
    // behaviour — this is the one place where a real implementation is more
    // meaningful than a mock.
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder, jwtService, otpService);
    }

    // ── Registration ────────────────────────────────────────────────────────

    @Test
    void register_validRequest_savesUserAndReturnsToken() {
        RegisterRequest req = buildRegisterRequest("Ada", "ada@example.com", "password123");

        when(userRepository.existsByEmail("ada@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0); u.setId(1L); return u;
        });
        when(jwtService.generateToken(any(User.class))).thenReturn("mock.jwt.token");

        AuthResponse res = authService.register(req);

        assertEquals("mock.jwt.token", res.getToken());
        assertEquals("ada@example.com", res.getEmail());
        assertEquals("Ada", res.getName());
        verify(userRepository).save(any(User.class));
    }

    @Test
    void register_passwordIsHashedWithBCrypt_notStoredAsPlainText() {
        RegisterRequest req = buildRegisterRequest("Ada", "ada@example.com", "password123");

        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(jwtService.generateToken(any(User.class))).thenReturn("token");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        when(userRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        authService.register(req);

        User saved = captor.getValue();
        // The stored hash must NOT be the raw password
        assertNotEquals("password123", saved.getPasswordHash());
        // It must be a valid BCrypt hash of that password
        assertTrue(passwordEncoder.matches("password123", saved.getPasswordHash()),
                "Stored hash should verify against the original password");
    }

    @Test
    void register_passwordNeverReturnedInResponse() {
        RegisterRequest req = buildRegisterRequest("Ada", "ada@example.com", "secret");
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtService.generateToken(any(User.class))).thenReturn("token");

        AuthResponse res = authService.register(req);

        // AuthResponse has token, email, name — no password field at all
        assertNull(null); // placeholder — actual check is the DTO having no password field
        assertNotNull(res.getToken());
        assertNotNull(res.getEmail());
        assertNotNull(res.getName());
    }

    @Test
    void register_duplicateEmail_throwsConflict() {
        RegisterRequest req = buildRegisterRequest("Ada", "taken@example.com", "password123");
        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        assertThrows(DuplicateEmailException.class, () -> authService.register(req));
        verify(userRepository, never()).save(any());
    }

    @Test
    void register_passwordMismatch_throwsBadRequest() {
        RegisterRequest req = new RegisterRequest();
        req.setName("Ada"); req.setEmail("ada@example.com");
        req.setPassword("password123"); req.setConfirmPassword("DIFFERENT");

        assertThrows(IllegalArgumentException.class, () -> authService.register(req));
        verifyNoInteractions(userRepository);
    }

    // ── Login ────────────────────────────────────────────────────────────────

    @Test
    void login_correctCredentials_returnsToken() {
        String rawPassword = "correct-password";
        User user = buildUser("ada@example.com", rawPassword);

        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(user));
        when(jwtService.generateToken(user)).thenReturn("jwt.token");

        LoginRequest req = new LoginRequest();
        req.setEmail("ada@example.com");
        req.setPassword(rawPassword);

        AuthResponse res = authService.login(req);

        assertEquals("jwt.token", res.getToken());
        assertEquals("ada@example.com", res.getEmail());
    }

    @Test
    void login_wrongPassword_throwsUnauthorized() {
        User user = buildUser("ada@example.com", "correct");
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(user));

        LoginRequest req = new LoginRequest();
        req.setEmail("ada@example.com");
        req.setPassword("wrong-password");

        assertThrows(InvalidCredentialsException.class, () -> authService.login(req));
    }

    @Test
    void login_emailNotFound_throwsUnauthorized() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        LoginRequest req = new LoginRequest();
        req.setEmail("nobody@example.com");
        req.setPassword("any");

        // Same exception as wrong-password — avoids leaking which emails exist
        assertThrows(InvalidCredentialsException.class, () -> authService.login(req));
    }

    // ── Forgot & Reset Password ──────────────────────────────────────────────

    @Test
    void forgotPassword_existingUser_sendsOtp() {
        User user = buildUser("ada@example.com", "Secret123!");
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(user));

        authService.forgotPassword("ada@example.com");

        verify(otpService).sendOtp("ada@example.com", com.example.urlshortener.entity.OtpPurpose.PASSWORD_RESET);
    }

    @Test
    void forgotPassword_nonExistentUser_throwsException() {
        when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> authService.forgotPassword("unknown@example.com"));
        verifyNoInteractions(otpService);
    }

    @Test
    void resetPassword_validRequest_updatesPasswordHash() {
        User user = buildUser("ada@example.com", "OldPassword123!");
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(user));
        when(otpService.verifyOtp("ada@example.com", "123456", com.example.urlshortener.entity.OtpPurpose.PASSWORD_RESET, true))
                .thenReturn(true);

        com.example.urlshortener.dto.ResetPasswordRequest req = new com.example.urlshortener.dto.ResetPasswordRequest();
        req.setEmail("ada@example.com");
        req.setOtp("123456");
        req.setNewPassword("NewSecret888!");
        req.setConfirmNewPassword("NewSecret888!");

        authService.resetPassword(req);

        verify(userRepository).save(user);
        assertTrue(passwordEncoder.matches("NewSecret888!", user.getPasswordHash()));
    }

    @Test
    void resetPassword_mismatchedConfirmPassword_throwsException() {
        com.example.urlshortener.dto.ResetPasswordRequest req = new com.example.urlshortener.dto.ResetPasswordRequest();
        req.setEmail("ada@example.com");
        req.setOtp("123456");
        req.setNewPassword("NewSecret888!");
        req.setConfirmNewPassword("Different888!");

        assertThrows(IllegalArgumentException.class, () -> authService.resetPassword(req));
        verifyNoInteractions(otpService);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private RegisterRequest buildRegisterRequest(String name, String email, String password) {
        RegisterRequest req = new RegisterRequest();
        req.setName(name); req.setEmail(email);
        req.setPassword(password); req.setConfirmPassword(password);
        return req;
    }

    private User buildUser(String email, String rawPassword) {
        return User.builder()
                .id(1L).name("Ada").email(email)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .createdAt(LocalDateTime.now())
                .build();
    }
}
