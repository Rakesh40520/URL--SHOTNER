package com.example.urlshortener.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;

/**
 * Redis-backed {@link RateLimiter}: every instance of this app shares the
 * same counters, so the limit is enforced correctly across a multi-instance
 * deployment - unlike {@link InMemoryRateLimiter}, where each instance
 * tracks its own counts and the effective limit is (maxRequests *
 * instanceCount). Active when {@code app.redis.enabled=true} - see
 * RedisRateLimitConfig.
 *
 * <p>The increment-and-set-expiry-on-first-hit sequence runs as a single
 * Lua script (EVAL), which Redis executes atomically - without that, two
 * concurrent requests could both INCR before either sets the TTL, or a key
 * could increment forever if a crash landed between the INCR and the
 * EXPIRE. This is the standard fixed-window-counter-in-Redis pattern.
 */
public class RedisRateLimiter implements RateLimiter {

    private static final RedisScript<Long> INCREMENT_AND_EXPIRE = new DefaultRedisScript<>(
            "local current = redis.call('INCR', KEYS[1]) " +
                    "if tonumber(current) == 1 then " +
                    "  redis.call('EXPIRE', KEYS[1], ARGV[1]) " +
                    "end " +
                    "return current",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    // Namespaces keys per RateLimiter instance (e.g. "shorten" vs "auth")
    // so the shorten and login limiters - which would otherwise both just
    // be keying on a raw client IP - don't collide on the same counter.
    private final String keyPrefix;
    private final int maxRequests;
    private final long windowSeconds;

    public RedisRateLimiter(StringRedisTemplate redisTemplate, String keyPrefix, int maxRequests, long windowSeconds) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = keyPrefix;
        this.maxRequests = maxRequests;
        this.windowSeconds = windowSeconds;
    }

    @Override
    public boolean tryConsume(String key) {
        String redisKey = "ratelimit:" + keyPrefix + ":" + key;
        Long count = redisTemplate.execute(INCREMENT_AND_EXPIRE,
                Collections.singletonList(redisKey), String.valueOf(windowSeconds));
        // A null result means the Redis call itself failed - fail open
        // (allow the request) rather than take the whole endpoint down
        // because the rate limiter's backing store had a hiccup.
        return count == null || count <= maxRequests;
    }
}
