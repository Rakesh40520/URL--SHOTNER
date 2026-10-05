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
public class ShortenResponse {
    private String shortCode;
    private String shortUrl;
    private String longUrl;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;

    // The RAW (unhashed) management key - only ever appears in this one
    // response, right after creation. The server only ever stores its
    // BCrypt hash after this point, so if the caller loses it, it's gone;
    // there's no "forgot my key" recovery, same as a password.
    private String managementKey;
}
