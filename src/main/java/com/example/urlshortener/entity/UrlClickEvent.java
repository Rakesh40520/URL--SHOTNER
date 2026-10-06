package com.example.urlshortener.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One row per click on a short URL. Kept separate from UrlMapping.clickCount
 * (which stays as a fast running total) so we can page through click history
 * without ever pulling a whole link's clicks into memory at once.
 */
@Entity
@Table(name = "url_click_event", indexes = {
        @Index(name = "idx_click_short_code", columnList = "shortCode")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UrlClickEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String shortCode;

    @Column(nullable = false)
    private LocalDateTime clickedAt;

    @Column(length = 64)
    private String ipAddress;

    // Who sent the click: the website it came from (host only) and/or an owner-set
    // ?ref= tag. Both null for direct/unknown traffic.
    @Column(length = 120)
    private String referrerHost;

    @Column(length = 30)
    private String referralTag;

    // Where the click came from, filled in shortly after the click by
    // ClickGeoEnricher. Null until then, or permanently if the IP is private/unknown.
    @Column(length = 100)
    private String country;

    @Column(length = 8)
    private String countryCode;

    @Column(length = 100)
    private String region;

    @Column(length = 100)
    private String city;

    // When a location lookup was last attempted (used by the retry job so a click
    // isn't retried constantly). Null = never attempted by the retry job.
    private LocalDateTime geoCheckedAt;
}
