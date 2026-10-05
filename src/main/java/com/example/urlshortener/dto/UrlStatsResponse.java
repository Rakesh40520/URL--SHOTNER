package com.example.urlshortener.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UrlStatsResponse {
    private String shortCode;
    private String longUrl;
    private long clickCount;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private LocalDateTime lastAccessedAt;
    private boolean expired;
    private String status; // "Active", "Flagged", or "Disabled"
    private String statusReason;
    private int reportCount;
}
