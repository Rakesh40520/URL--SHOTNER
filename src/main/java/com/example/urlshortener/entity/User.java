package com.example.urlshortener.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

// Table is "app_user", not "user" - "user" is a reserved word in Postgres
// (and some other DBs), and this project already has an optional postgres
// profile, so it's worth avoiding that trap now rather than later.
@Entity
@Table(name = "app_user", uniqueConstraints = @UniqueConstraint(columnNames = "email"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    // Never the raw password - always a BCrypt hash (see AuthService).
    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    // Nullable on purpose: accounts created before subscriptions existed have
    // no value here, and ddl-auto=update adds the column without a default.
    // Always read it through getEffectivePlan(), which maps null -> FREE.
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private SubscriptionPlan plan;

    // When the user's current plan was last changed (null = never changed).
    private LocalDateTime planChangedAt;

    public SubscriptionPlan getEffectivePlan() {
        return plan == null ? SubscriptionPlan.FREE : plan;
    }
}
