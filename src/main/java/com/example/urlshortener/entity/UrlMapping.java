package com.example.urlshortener.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "url_mapping", indexes = {
        @Index(name = "idx_short_code", columnList = "shortCode", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UrlMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 2048)
    private String longUrl;

    @Column(nullable = false, unique = true, length = 20)
    private String shortCode;

    @Column(nullable = false)
    private boolean customAlias;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime expiresAt;

    private LocalDateTime lastAccessedAt;

    @Column(nullable = false)
    @Builder.Default
    private long clickCount = 0L;

    // Nullable on purpose: null means an anonymous URL (no owner), which is
    // how every URL worked before auth existed and how anonymous URLs still
    // work now - this column is additive, not a breaking change to the
    // existing table.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    // BCrypt hash of a one-time secret shown to the creator right after
    // dispatch (see ShortenResponse.managementKey) - never stored or
    // returned in plaintext again after that first response, same pattern
    // as a user's password. Required to view stats/clicks for this link
    // unless the requester is its authenticated owner. Nullable so rows
    // created before this feature existed don't break on schema update;
    // such rows simply can't be viewed by key (only by owner, if any).
    private String managementKeyHash;

    // ACTIVE unless the owner disabled it or the Safe Browsing check (at
    // creation or on the scheduled re-check) flagged it. Old rows loaded
    // before this column existed get ACTIVE via the default below, not
    // null - resolve() branches on this enum directly.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private UrlStatus status = UrlStatus.ACTIVE;

    // Human-readable reason the link is FLAGGED/DISABLED, e.g. "Blocked at
    // creation: SOCIAL_ENGINEERING" or "Disabled by owner". Null while
    // ACTIVE. Shown to the owner in "My Dispatches", never to an anonymous
    // caller hitting the redirect (see UrlBlockedException).
    @Column(length = 255)
    private String statusReason;

    // How many times the public "report this link" endpoint has been used
    // against this short code. Reporting is itself rate-limited per IP
    // (see RateLimitFilter/report-rate-limit) so this can't be trivially
    // inflated by one caller, but it's still a signal, not proof - crossing
    // app.report.auto-disable-threshold flags the link for review rather
    // than being treated as certain abuse.
    @Column(nullable = false)
    @Builder.Default
    private int reportCount = 0;

    // When the scheduled Safe Browsing re-check last examined this link's
    // longUrl. Null means it's only ever been checked at creation time (or
    // Safe Browsing was disabled/unreachable then) - see
    // UrlShortenerService.recheckActiveLinksForThreats.
    private LocalDateTime lastSafetyCheckAt;

    public boolean isExpired() {
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }

    public boolean isBlocked() {
        return status != UrlStatus.ACTIVE;
    }
}
