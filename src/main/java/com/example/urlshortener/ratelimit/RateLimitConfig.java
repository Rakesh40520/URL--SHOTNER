package com.example.urlshortener.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires up one RateLimiter per protected endpoint group, each with its own
 * config. Login/register get a much tighter budget than link creation
 * since credential-stuffing/brute-force attempts are the realistic threat
 * there, while anonymous link creation is throttled mainly to stop scripted
 * abuse of the shortening endpoint itself.
 *
 * <p>In-memory implementation - the default, active unless
 * {@code app.redis.enabled=true}, in which case RedisRateLimitConfig
 * supplies these same three bean names backed by Redis instead. Exactly
 * one of the two configs is ever active, so RateLimitFilter (which depends
 * only on the RateLimiter interface) doesn't need to know or care which.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.redis", name = "enabled", havingValue = "false", matchIfMissing = true)
public class RateLimitConfig {

    @Bean
    public RateLimiter shortenRateLimiter(
            @Value("${app.rate-limit.max-requests:20}") int maxRequests,
            @Value("${app.rate-limit.window-seconds:60}") long windowSeconds) {
        return new InMemoryRateLimiter(maxRequests, windowSeconds);
    }

    @Bean
    public RateLimiter authRateLimiter(
            @Value("${app.auth-rate-limit.max-requests:10}") int maxRequests,
            @Value("${app.auth-rate-limit.window-seconds:60}") long windowSeconds) {
        return new InMemoryRateLimiter(maxRequests, windowSeconds);
    }

    // Deliberately tight - the report endpoint takes no auth and no
    // management key, so without its own strict budget it would be the
    // easiest of the three rate-limited endpoints to abuse (e.g. one caller
    // spamming reports to auto-flag someone else's legitimate link via
    // app.report.auto-disable-threshold).
    @Bean
    public RateLimiter reportRateLimiter(
            @Value("${app.report-rate-limit.max-requests:5}") int maxRequests,
            @Value("${app.report-rate-limit.window-seconds:60}") long windowSeconds) {
        return new InMemoryRateLimiter(maxRequests, windowSeconds);
    }
}
