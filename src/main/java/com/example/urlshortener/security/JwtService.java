package com.example.urlshortener.security;

import com.example.urlshortener.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.Optional;

/**
 * Issues and validates JWTs. Tokens are signed with HMAC-SHA256 using a
 * shared secret (see "jwt.secret" in application.yml - override it with the
 * JWT_SECRET env var in any real deployment; the default is fine for local
 * dev only). The token's subject is the user's email, which is how
 * {@link JwtAuthenticationFilter} looks the user back up on each request.
 */
@Component
public class JwtService {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration-ms:86400000}")
    private long expirationMs;

    public String generateToken(User user) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .setSubject(user.getEmail())
                .claim("name", user.getName())
                .claim("userId", user.getId())
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(signingKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public String extractEmail(String token) {
        return parseClaims(token).getSubject();
    }

    public boolean isValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException ex) {
            // Covers expired, malformed, or signed-with-a-different-key
            // tokens - all treated the same way: not valid.
            return false;
        }
    }

    /**
     * Combines {@link #isValid} and {@link #extractEmail} into a single
     * parse. JwtAuthenticationFilter runs on every request and used to call
     * isValid() then, only if that passed, extractEmail() - parsing (and
     * re-verifying the HMAC signature of) the same token twice per request.
     * This does the work once: empty means the token was missing/expired/
     * malformed/tampered, present means it parsed and holds the subject.
     */
    public Optional<String> extractEmailIfValid(String token) {
        try {
            return Optional.of(parseClaims(token).getSubject());
        } catch (JwtException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(signingKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private Key signingKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
