package com.example.urlshortener.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    private InMemoryRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        // Config now comes through the constructor (see RateLimitConfig),
        // since different endpoints need independently tuned limiters -
        // no more reaching into @Value fields via reflection.
        rateLimiter = new InMemoryRateLimiter(3, 60);
    }

    @Test
    void allowsUpToTheConfiguredLimit() {
        assertTrue(rateLimiter.tryConsume("1.2.3.4"));
        assertTrue(rateLimiter.tryConsume("1.2.3.4"));
        assertTrue(rateLimiter.tryConsume("1.2.3.4"));
    }

    @Test
    void rejectsOnceLimitExceededWithinWindow() {
        rateLimiter.tryConsume("5.6.7.8");
        rateLimiter.tryConsume("5.6.7.8");
        rateLimiter.tryConsume("5.6.7.8");

        assertFalse(rateLimiter.tryConsume("5.6.7.8"));
    }

    @Test
    void tracksDifferentKeysIndependently() {
        rateLimiter.tryConsume("9.9.9.9");
        rateLimiter.tryConsume("9.9.9.9");
        rateLimiter.tryConsume("9.9.9.9");
        assertFalse(rateLimiter.tryConsume("9.9.9.9"));

        // A different client shouldn't be affected by the first one's limit.
        assertTrue(rateLimiter.tryConsume("10.10.10.10"));
    }

    @Test
    void independentInstancesDoNotShareBudget() {
        // Simulates shortenRateLimiter vs authRateLimiter: two separately
        // configured instances must not share counters for the same key.
        InMemoryRateLimiter other = new InMemoryRateLimiter(1, 60);

        assertTrue(rateLimiter.tryConsume("1.1.1.1"));
        assertTrue(rateLimiter.tryConsume("1.1.1.1"));
        assertTrue(rateLimiter.tryConsume("1.1.1.1"));
        assertFalse(rateLimiter.tryConsume("1.1.1.1"));

        // "other" has its own budget for the same key.
        assertTrue(other.tryConsume("1.1.1.1"));
        assertFalse(other.tryConsume("1.1.1.1"));
    }
}
