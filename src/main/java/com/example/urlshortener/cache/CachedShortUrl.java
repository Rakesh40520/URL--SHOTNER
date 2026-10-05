package com.example.urlshortener.cache;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * What gets cached per short code for the redirect hot path - deliberately
 * NOT the whole UrlMapping entity. A few reasons:
 * <ul>
 *   <li>Serialization: UrlMapping carries a lazy @ManyToOne User association
 *       that Jackson (used for the Redis value serializer - see
 *       config/CacheConfig) can't safely serialize without either forcing
 *       it to load or producing a broken proxy on the way back out.</li>
 *   <li>Correctness: only ACTIVE, not-yet-expired links are ever put in
 *       this cache (see UrlShortenerService.resolve) - so there's no
 *       status field to cache at all, and expiresAt is kept alongside
 *       longUrl purely so a cache hit can still catch a link that expired
 *       since it was cached, without a DB round trip to find out.</li>
 *   <li>Size: this is looked up on every single redirect, so keeping the
 *       cached value tiny keeps that path fast regardless of backend.</li>
 * </ul>
 */
public record CachedShortUrl(String longUrl, LocalDateTime expiresAt) implements Serializable {

    public boolean isExpired() {
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }
}
