package com.example.urlshortener.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A one-time 6-digit code emailed to a user (to verify their address or to reset
 * their password). Only a bcrypt hash of the code is stored, never the code itself.
 * The newest row for an (email, purpose) pair is the only one that counts; older
 * rows are kept briefly so the hourly send limit can be enforced, then cleaned up.
 */
@Entity
@Table(name = "email_code", indexes = @Index(name = "idx_email_code_lookup", columnList = "email, purpose, createdAt"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 254)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private EmailCodePurpose purpose;

    @Column(nullable = false)
    private String codeHash;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    // Wrong guesses so far; the code is burned once this reaches the limit.
    @Column(nullable = false)
    private int attempts;
}
