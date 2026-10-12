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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private JwtService jwtService;
    @Mock private EmailCodeService emailCodeService;

    // Real BCrypt so we can verify actual hashing behaviour.
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder, jwtService, emailCodeService);
    }

    // ── Registration ────────────────────────────────────────────────────────

    @Test
    void register_newUser_savesUnverified_sendsCode_andDoesNotLogIn() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        when(userRepository.save(saved.capture())).thenAnswer(inv -> inv.getArgument(0));

        RegisterResponse res = authService.register(buildRegisterRequest("Ada", "Ada@Example.com", "password123"));

        assertTrue(res.isVerificationRequired());
        assertEquals("ada@example.com", res.getEmail());
        assertEquals(Boolean.FALSE, saved.getValue().getEmailVerified());
        assertEquals("ada@example.com", saved.getValue().getEmail());          // stored lower-cased
        verify(emailCodeService).issue("ada@example.com", EmailCodePurpose.VERIFY_EMAIL, "Ada");
        verifyNoInteractions(jwtService);                                        // no token until verified
    }

    @Test
    void register_passwordIsHashedWithBCrypt_notStoredAsPlainText() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        when(userRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        authService.register(buildRegisterRequest("Ada", "ada@example.com", "password123"));

        User saved = captor.getValue();
        assertNotEquals("password123", saved.getPasswordHash());
        assertTrue(passwordEncoder.matches("password123", saved.getPasswordHash()));
    }

    @Test
    void register_verifiedEmailAlreadyExists_throwsConflict() {
        User existing = buildUser("taken@example.com", "x");
        existing.setEmailVerified(true);
        when(userRepository.findByEmail("taken@example.com")).thenReturn(Optional.of(existing));

        assertThrows(DuplicateEmailException.class,
                () -> authService.register(buildRegisterRequest("Ada", "taken@example.com", "password123")));
        verify(userRepository, never()).save(any());
        verifyNoInteractions(emailCodeService);
    }

    @Test
    void register_legacyAccountWithNoFlag_countsAsVerified_soItIsAConflict() {
        User legacy = buildUser("old@example.com", "x");   // emailVerified == null (pre-verification account)
        when(userRepository.findByEmail("old@example.com")).thenReturn(Optional.of(legacy));

        assertThrows(DuplicateEmailException.class,
                () -> authService.register(buildRegisterRequest("Ada", "old@example.com", "password123")));
    }

    @Test
    void register_unverifiedEmailAlreadyExists_updatesAccountAndResendsCode() {
        User pending = buildUser("pending@example.com", "old-password");
        pending.setEmailVerified(false);
        when(userRepository.findByEmail("pending@example.com")).thenReturn(Optional.of(pending));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        authService.register(buildRegisterRequest("New Name", "pending@example.com", "new-password-1"));

        assertEquals("New Name", pending.getName());
        assertTrue(passwordEncoder.matches("new-password-1", pending.getPasswordHash()));
        verify(emailCodeService).issue("pending@example.com", EmailCodePurpose.VERIFY_EMAIL, "New Name");
    }

    @Test
    void register_passwordMismatch_throwsBadRequest() {
        RegisterRequest req = new RegisterRequest();
        req.setName("Ada"); req.setEmail("ada@example.com");
        req.setPassword("password123"); req.setConfirmPassword("DIFFERENT");

        assertThrows(IllegalArgumentException.class, () -> authService.register(req));
        verifyNoInteractions(userRepository);
    }

    @Test
    void register_whenEmailCannotBeSent_propagatesTheFailure() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new EmailDeliveryException("boom", null)).when(emailCodeService).issue(anyString(), any(), any());

        assertThrows(EmailDeliveryException.class,
                () -> authService.register(buildRegisterRequest("Ada", "ada@example.com", "password123")));
    }

    // ── Email verification ───────────────────────────────────────────────────

    @Test
    void verifyEmail_correctCode_marksVerified_andReturnsToken() {
        User user = buildUser("ada@example.com", "pw");
        user.setEmailVerified(false);
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(user));
        when(jwtService.generateToken(user)).thenReturn("jwt.token");

        AuthResponse res = authService.verifyEmail("ada@example.com", "123456");

        verify(emailCodeService).verify("ada@example.com", EmailCodePurpose.VERIFY_EMAIL, "123456");
        assertEquals(Boolean.TRUE, user.getEmailVerified());
        assertEquals("jwt.token", res.getToken());
        verify(userRepository).save(user);
    }

    @Test
    void verifyEmail_wrongCode_doesNotVerify() {
        User user = buildUser("ada@example.com", "pw");
        user.setEmailVerified(false);
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(user));
        doThrow(new InvalidCodeException("Incorrect code. 4 attempts left.", 4))
                .when(emailCodeService).verify(anyString(), any(), anyString());

        assertThrows(InvalidCodeException.class, () -> authService.verifyEmail("ada@example.com", "000000"));
        assertEquals(Boolean.FALSE, user.getEmailVerified());
        verify(userRepository, never()).save(any());
        verifyNoInteractions(jwtService);
    }

    @Test
    void verifyEmail_unknownEmail_looksLikeAnInvalidCode() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
        assertThrows(InvalidCodeException.class, () -> authService.verifyEmail("nobody@example.com", "123456"));
    }

    @Test
    void resendVerification_onlySendsForUnverifiedAccounts() {
        User unverified = buildUser("a@example.com", "pw"); unverified.setEmailVerified(false);
        User verified = buildUser("b@example.com", "pw"); verified.setEmailVerified(true);
        when(userRepository.findByEmail("a@example.com")).thenReturn(Optional.of(unverified));
        when(userRepository.findByEmail("b@example.com")).thenReturn(Optional.of(verified));
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        authService.resendVerification("a@example.com");
        authService.resendVerification("b@example.com");
        authService.resendVerification("ghost@example.com");

        verify(emailCodeService, times(1)).issue(anyString(), eq(EmailCodePurpose.VERIFY_EMAIL), any());
        verify(emailCodeService).issue(eq("a@example.com"), eq(EmailCodePurpose.VERIFY_EMAIL), any());
    }

    // ── Login ────────────────────────────────────────────────────────────────

    @Test
    void login_correctCredentials_returnsToken() {
        User user = buildUser("ada@example.com", "correct-password");
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(user));
        when(jwtService.generateToken(user)).thenReturn("jwt.token");

        AuthResponse res = authService.login(loginRequest("ada@example.com", "correct-password"));

        assertEquals("jwt.token", res.getToken());
        assertEquals("ada@example.com", res.getEmail());
    }

    @Test
    void login_unverifiedAccount_rightPassword_isRefusedUntilVerified() {
        User user = buildUser("ada@example.com", "correct-password");
        user.setEmailVerified(false);
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(user));

        EmailNotVerifiedException ex = assertThrows(EmailNotVerifiedException.class,
                () -> authService.login(loginRequest("ada@example.com", "correct-password")));
        assertEquals("ada@example.com", ex.getEmail());
        verifyNoInteractions(jwtService);
    }

    @Test
    void login_unverifiedAccount_wrongPassword_doesNotRevealItExists() {
        User user = buildUser("ada@example.com", "correct-password");
        user.setEmailVerified(false);
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(user));

        assertThrows(InvalidCredentialsException.class,
                () -> authService.login(loginRequest("ada@example.com", "wrong")));
    }

    @Test
    void login_wrongPassword_throwsUnauthorized() {
        User user = buildUser("ada@example.com", "correct");
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(user));

        assertThrows(InvalidCredentialsException.class,
                () -> authService.login(loginRequest("ada@example.com", "wrong-password")));
    }

    @Test
    void login_emailNotFound_throwsUnauthorized() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        // Same exception as wrong-password — avoids leaking which emails exist
        assertThrows(InvalidCredentialsException.class,
                () -> authService.login(loginRequest("nobody@example.com", "any")));
    }

    // ── Forgot / reset password ──────────────────────────────────────────────

    @Test
    void forgotPassword_knownEmail_sendsResetCode() {
        User user = buildUser("ada@example.com", "pw");
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(user));

        authService.forgotPassword("ada@example.com");

        verify(emailCodeService).issue("ada@example.com", EmailCodePurpose.RESET_PASSWORD, "Ada");
    }

    @Test
    void forgotPassword_unknownEmail_doesNothing_andDoesNotThrow() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> authService.forgotPassword("nobody@example.com"));
        verifyNoInteractions(emailCodeService);
    }

    @Test
    void forgotPassword_deliveryFailure_isSwallowed_soTheAnswerStaysTheSame() {
        User user = buildUser("ada@example.com", "pw");
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(user));
        doThrow(new EmailDeliveryException("provider down", null)).when(emailCodeService).issue(anyString(), any(), any());

        assertDoesNotThrow(() -> authService.forgotPassword("ada@example.com"));
    }

    @Test
    void resetPassword_correctCode_setsNewPassword_andVerifiesAccount() {
        User user = buildUser("ada@example.com", "old-password");
        user.setEmailVerified(false);
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(user));

        authService.resetPassword(resetRequest("ada@example.com", "123456", "brand-new-pass", "brand-new-pass"));

        verify(emailCodeService).verify("ada@example.com", EmailCodePurpose.RESET_PASSWORD, "123456");
        assertTrue(passwordEncoder.matches("brand-new-pass", user.getPasswordHash()));
        assertFalse(passwordEncoder.matches("old-password", user.getPasswordHash()));
        assertEquals(Boolean.TRUE, user.getEmailVerified());
    }

    @Test
    void resetPassword_wrongCode_leavesPasswordUnchanged() {
        User user = buildUser("ada@example.com", "old-password");
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(user));
        doThrow(InvalidCodeException.expiredOrUnknown()).when(emailCodeService).verify(anyString(), any(), anyString());

        assertThrows(InvalidCodeException.class,
                () -> authService.resetPassword(resetRequest("ada@example.com", "000000", "brand-new-pass", "brand-new-pass")));
        assertTrue(passwordEncoder.matches("old-password", user.getPasswordHash()));
        verify(userRepository, never()).save(any());
    }

    @Test
    void resetPassword_mismatchedConfirmation_isRejectedBeforeAnythingElse() {
        assertThrows(IllegalArgumentException.class,
                () -> authService.resetPassword(resetRequest("ada@example.com", "123456", "one-password-1", "another-pass-2")));
        verifyNoInteractions(userRepository, emailCodeService);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private RegisterRequest buildRegisterRequest(String name, String email, String password) {
        RegisterRequest req = new RegisterRequest();
        req.setName(name); req.setEmail(email);
        req.setPassword(password); req.setConfirmPassword(password);
        return req;
    }

    private LoginRequest loginRequest(String email, String password) {
        LoginRequest req = new LoginRequest();
        req.setEmail(email); req.setPassword(password);
        return req;
    }

    private ResetPasswordRequest resetRequest(String email, String code, String pw, String confirm) {
        ResetPasswordRequest r = new ResetPasswordRequest();
        r.setEmail(email); r.setCode(code); r.setNewPassword(pw); r.setConfirmPassword(confirm);
        return r;
    }

    private User buildUser(String email, String rawPassword) {
        return User.builder()
                .id(1L).name("Ada").email(email)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .createdAt(LocalDateTime.now())
                .build();
    }
}
