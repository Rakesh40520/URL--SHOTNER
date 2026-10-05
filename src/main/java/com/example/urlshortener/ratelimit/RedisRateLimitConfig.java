package com.example.urlshortener.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis-backed equivalent of RateLimitConfig, active only when
 * {@code app.redis.enabled=true}. Supplies the exact same three bean names
 * (shortenRateLimiter/authRateLimiter/reportRateLimiter) so RateLimitFilter
 * doesn't change at all between the two - only which of these two
 * @Configuration classes is active does.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.redis", name = "enabled", havingValue = "true")
public class RedisRateLimitConfig {

    @Bean
    public RateLimiter shortenRateLimiter(
            StringRedisTemplate redisTemplate,
            @Value("${app.rate-limit.max-requests:20}") int maxRequests,
            @Value("${app.rate-limit.window-seconds:60}") long windowSeconds) {
        return new RedisRateLimiter(redisTemplate, "shorten", maxRequests, windowSeconds);
    }

    @Bean
    public RateLimiter authRateLimiter(
            StringRedisTemplate redisTemplate,
            @Value("${app.auth-rate-limit.max-requests:10}") int maxRequests,
            @Value("${app.auth-rate-limit.window-seconds:60}") long windowSeconds) {
        return new RedisRateLimiter(redisTemplate, "auth", maxRequests, windowSeconds);
    }

    @Bean
    public RateLimiter reportRateLimiter(
            StringRedisTemplate redisTemplate,
            @Value("${app.report-rate-limit.max-requests:5}") int maxRequests,
            @Value("${app.report-rate-limit.window-seconds:60}") long windowSeconds) {
        return new RedisRateLimiter(redisTemplate, "report", maxRequests, windowSeconds);
    }
}
