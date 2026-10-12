package com.example.urlshortener.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** The signed-in user's current plan plus how much of this month's quota they've used. */
@Getter
@Builder
public class SubscriptionResponse {
    private PlanResponse plan;
    private long linksUsedThisMonth;
    private int monthlyLinkLimit;       // -1 = unlimited
    private long linksRemaining;        // -1 = unlimited
    private LocalDate quotaResetsOn;    // first day of next month
    private LocalDateTime planChangedAt;
    private boolean selfServiceEnabled; // false => upgrades must go through real checkout
}
