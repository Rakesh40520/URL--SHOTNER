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
}
