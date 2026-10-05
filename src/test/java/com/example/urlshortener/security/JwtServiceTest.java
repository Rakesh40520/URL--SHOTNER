package com.example.urlshortener.security;

import com.example.urlshortener.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private JwtService jwtService;

    // 32-byte minimum for HS256 (jjwt enforces this)
    private static final String SECRET =
            "test-secret-key-at-least-32-bytes-long!!";

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secret", SECRET);
        ReflectionTestUtils.setField(jwtService, "expirationMs", 86_400_000L); // 24 h
    }

    private static User testUser() {
        return User.builder()
                .id(1L).name("Ada Lovelace")
                .email("ada@example.com")
                .passwordHash("hashed")
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Test
    void generateToken_returnsNonBlankString() {
        String token = jwtService.generateToken(testUser());
        assertNotNull(token);
        assertFalse(token.isBlank());
    }

    @Test
    void generateToken_extractEmail_roundTrips() {
        User user = testUser();
        String token = jwtService.generateToken(user);
        assertEquals("ada@example.com", jwtService.extractEmail(token));
    }

    @Test
    void isValid_freshToken_returnsTrue() {
        assertTrue(jwtService.isValid(jwtService.generateToken(testUser())));
    }

    @Test
    void isValid_expiredToken_returnsFalse() {
        // Generate a token that expires immediately (1 ms in the past)
        ReflectionTestUtils.setField(jwtService, "expirationMs", -1L);
        String expiredToken = jwtService.generateToken(testUser());

        ReflectionTestUtils.setField(jwtService, "expirationMs", 86_400_000L);
        assertFalse(jwtService.isValid(expiredToken));
    }

    @Test
    void isValid_tamperedToken_returnsFalse() {
        String token = jwtService.generateToken(testUser());
        // Corrupt the signature segment (last part after the second dot)
        String tampered = token.substring(0, token.lastIndexOf('.') + 1) + "INVALIDSIG";
        assertFalse(jwtService.isValid(tampered));
    }

    @Test
    void isValid_completelyGarbage_returnsFalse() {
        assertFalse(jwtService.isValid("not.a.jwt"));
        assertFalse(jwtService.isValid(""));
    }

    @Test
    void isValid_signedWithDifferentSecret_returnsFalse() {
        // A token signed by a different server (or after a secret rotation)
        JwtService other = new JwtService();
        ReflectionTestUtils.setField(other, "secret", "completely-different-secret-32bytes!!");
        ReflectionTestUtils.setField(other, "expirationMs", 86_400_000L);

        String foreignToken = other.generateToken(testUser());
        assertFalse(jwtService.isValid(foreignToken));
    }

    @Test
    void extractEmailIfValid_freshToken_returnsEmail() {
        String token = jwtService.generateToken(testUser());
        assertEquals("ada@example.com", jwtService.extractEmailIfValid(token).orElse(null));
    }

    @Test
    void extractEmailIfValid_invalidToken_returnsEmpty() {
        assertTrue(jwtService.extractEmailIfValid("not.a.jwt").isEmpty());
    }
}
