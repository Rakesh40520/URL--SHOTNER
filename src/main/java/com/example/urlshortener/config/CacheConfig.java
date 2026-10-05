package com.example.urlshortener.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Cache-aside for the redirect hot path (see
 * UrlShortenerService.resolve/CachedShortUrl). Two mutually exclusive
 * CacheManager beans, the same on/off switch as the rate limiter:
 *
 * <ul>
 *   <li>{@code app.redis.enabled=false} (default) - an in-memory
 *       ConcurrentMapCacheManager. Works out of the box, but - like
 *       InMemoryRateLimiter - is per-instance: each instance of this app
 *       would warm its own cache independently, and a disable/report on
 *       one instance wouldn't evict the entry cached on another.</li>
 *   <li>{@code app.redis.enabled=true} - a real RedisCacheManager shared
 *       by every instance, so an eviction on one instance is visible to
 *       all of them immediately.</li>
 * </ul>
 *
 * Both are named "shortUrls" so UrlShortenerService's
 * {@code cacheManager.getCache(SHORT_URL_CACHE)} call works identically
 * either way.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String SHORT_URL_CACHE = "shortUrls";

    @Bean
    @ConditionalOnProperty(prefix = "app.redis", name = "enabled", havingValue = "false", matchIfMissing = true)
    public CacheManager inMemoryCacheManager() {
        return new ConcurrentMapCacheManager(SHORT_URL_CACHE);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.redis", name = "enabled", havingValue = "true")
    public CacheManager redisCacheManager(RedisConnectionFactory connectionFactory, ObjectMapper objectMapper,
                                           @Value("${app.cache.short-url-ttl-seconds:600}") long ttlSeconds) {
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                // Belt-and-braces alongside the expiresAt check inside
                // CachedShortUrl itself: even if an eviction is ever missed
                // somewhere, an entry can't outlive this TTL.
                .entryTtl(Duration.ofSeconds(ttlSeconds))
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        new GenericJackson2JsonRedisSerializer(objectMapper)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }
}
