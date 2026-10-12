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
public class ClickResponse {
    private LocalDateTime clickedAt;
    private String ipAddress;
    private String country;
    private String region;
    private String city;
    private String referrerHost;
    private String referralTag;
}
