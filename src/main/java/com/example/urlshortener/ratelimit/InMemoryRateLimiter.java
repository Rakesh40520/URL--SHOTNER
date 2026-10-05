package com.example.urlshortener.ratelimit;

import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link RateLimiter}: a fixed-window counter, where each key
 * (typically a client IP) gets up to {@code maxRequests} requests per
 * {@code windowSeconds}; once the window elapses, the counter resets.
 *
 * This is intentionally simple and in-memory - correct for a single
 * instance, which fits a resume/portfolio project. It is NOT distributed:
 * if you ran multiple instances of this app behind a load balancer, each
 * instance would track its own counters independently, so the effective
 * limit would be (maxRequests * instanceCount). The default when
 * {@code app.redis.enabled} is false or unset - see RedisRateLimiter for
 * the distributed alternative and RateLimitConfig/RedisRateLimitConfig for
 * how the choice between them is made.
 *
 * <p>Takes its config via constructor rather than {@code @Value} fields (and
 * is instantiated by RateLimitConfig rather than being a {@code @Component}
 * itself) because different endpoints need independently tuned limits - a
 * much stricter one for login attempts than for anonymous link creation, for
 * example - and each needs its own counters so they don't share a budget.
 */
public class InMemoryRateLimiter implements RateLimiter {

    private final int maxRequests;
    private final long windowSeconds;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public InMemoryRateLimiter(int maxRequests, long windowSeconds) {
        this.maxRequests = maxRequests;
        this.windowSeconds = windowSeconds;
    }

    /**
     * @return true if the request should be allowed, false if the caller has
     *         exceeded the limit for the current window.
     */
    @Override
    public boolean tryConsume(String key) {
        long now = System.currentTimeMillis();
        Window window = windows.computeIfAbsent(key, k -> new Window(now));

        synchronized (window) {
            long windowMillis = Duration.ofSeconds(windowSeconds).toMillis();
            if (now - window.startedAt > windowMillis) {
                window.startedAt = now;
                window.count = 0;
            }
            if (window.count < maxRequests) {
                window.count++;
                return true;
            }
            return false;
        }
    }

    // Runs every 10 minutes and drops windows that haven't been touched
    // recently, so the map doesn't grow forever as new IPs show up. Works
    // even though this class isn't a @Component itself, since @Scheduled
    // processing applies to any singleton bean - and RateLimitConfig
    // registers each instance as one.
    @Scheduled(fixedRate = 10 * 60 * 1000)
    public void cleanupStaleWindows() {
        long cutoff = System.currentTimeMillis() - Duration.ofMinutes(30).toMillis();
        windows.entrySet().removeIf(entry -> entry.getValue().startedAt < cutoff);
    }

    int size() {
        return windows.size();
    }

    private static final class Window {
        volatile long startedAt;
        int count;

        Window(long startedAt) {
            this.startedAt = startedAt;
        }
    }
}
