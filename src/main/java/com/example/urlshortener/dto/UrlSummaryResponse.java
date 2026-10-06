package com.example.urlshortener.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
@Builder
public class UrlSummaryResponse {
    private String shortCode;
    private String shortUrl;
    private String longUrl;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private long clickCount;
    private String status; // "Active", "Expired", "Flagged", or "Disabled"
    private String statusReason; // null unless status is "Flagged" or "Disabled"
    private String managementKey; // owner's own list only; null if not stored (older links)
}
