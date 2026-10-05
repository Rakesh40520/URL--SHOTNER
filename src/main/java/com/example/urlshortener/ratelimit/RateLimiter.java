package com.example.urlshortener.ratelimit;

/**
 * A per-key fixed-window rate limiter. Each protected endpoint group
 * (shorten, auth, report) gets its own instance with its own budget - see
 * RateLimitConfig (in-memory, the default) and RedisRateLimitConfig
 * (distributed, when {@code app.redis.enabled=true}) for how a concrete
 * implementation gets chosen. RateLimitFilter depends only on this
 * interface, so it works identically either way.
 */
public interface RateLimiter {

    /**
     * @return true if the request should be allowed, false if the caller
     *         has exceeded the limit for the current window.
     */
    boolean tryConsume(String key);
}
