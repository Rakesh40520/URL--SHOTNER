package com.example.urlshortener.service;

import com.example.urlshortener.dto.ClickResponse;
import com.example.urlshortener.dto.ReportResponse;
import com.example.urlshortener.dto.ShortenRequest;
import com.example.urlshortener.dto.ShortenResponse;
import com.example.urlshortener.dto.UrlDashboardSummaryResponse;
import com.example.urlshortener.dto.UrlStatsResponse;
import com.example.urlshortener.dto.UrlSummaryResponse;
import com.example.urlshortener.entity.UrlClickEvent;
import com.example.urlshortener.entity.UrlMapping;
import com.example.urlshortener.entity.UrlStatus;
import com.example.urlshortener.entity.User;
import com.example.urlshortener.exception.AliasAlreadyExistsException;
import com.example.urlshortener.exception.ForbiddenException;
import com.example.urlshortener.exception.InvalidManagementKeyException;
import com.example.urlshortener.exception.InvalidUrlException;
import com.example.urlshortener.exception.UnsafeUrlException;
import com.example.urlshortener.exception.UrlBlockedException;
import com.example.urlshortener.exception.UrlExpiredException;
import com.example.urlshortener.exception.UrlNotFoundException;
import com.example.urlshortener.kafka.ClickEventPublisher;
import com.example.urlshortener.repository.UrlClickEventRepository;
import com.example.urlshortener.repository.UrlMappingRepository;
import com.example.urlshortener.safebrowsing.SafeBrowsingClient;
import com.example.urlshortener.safebrowsing.SafeBrowsingResult;
import com.example.urlshortener.sharding.ShardContext;
import com.example.urlshortener.sharding.ShardResolver;
import com.example.urlshortener.util.RandomCodeGenerator;
import com.example.urlshortener.util.ManagementKeyGenerator;
import com.example.urlshortener.cache.CachedShortUrl;
import com.example.urlshortener.config.CacheConfig;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
// Spring's own @Transactional (not jakarta.transaction.Transactional, which
// this used to import) - required because only Spring's version supports
// readOnly, which is what ReplicationRoutingDataSource keys its
// primary/replica decision on (see config/datasource). Using the JTA
// annotation here would silently make every read-only method below route
// to the primary under the postgres-ha profile - readOnly is the whole
// mechanism, so it has to be one Spring actually understands.
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UrlShortenerService {

    private static final Logger log = LoggerFactory.getLogger(UrlShortenerService.class);

    // Length of an auto-generated short code. 62^7 (~3.5 trillion) possible
    // codes keeps collisions vanishingly rare at any realistic scale for
    // this app - see shortenWithGeneratedCode.
    private static final int GENERATED_CODE_LENGTH = 7;
    private static final int MAX_GENERATION_ATTEMPTS = 5;

    private final UrlMappingRepository repository;
    private final UrlClickEventRepository clickEventRepository;
    private final PasswordEncoder passwordEncoder;
    private final SafeBrowsingClient safeBrowsingClient;
    // Nullable in practice only inside plain-Mockito unit tests, which don't
    // spin up a Spring context and so never supply one - every cacheManager
    // use below is null-safe for exactly that reason. In the running app
    // this is always one of CacheConfig's two beans (in-memory or Redis).
    private final CacheManager cacheManager;
    // Always injected (see ClickEventPublisher's own doc - the bean is
    // cheap/lazy regardless of app.kafka.enabled), but only actually used
    // by recordClick below when the flag is on.
    private final ClickEventPublisher clickEventPublisher;
    // Always injected regardless of profile - see ShardResolver's own doc.
    // Harmless outside postgres-sharded: nothing reads ShardContext unless
    // ShardRoutingDataSource is actually the active DataSource (see
    // ShardDataSourceConfig, @Profile("postgres-sharded")), so setting it
    // below is a no-op everywhere else regardless of what shard index
    // gets computed.
    private final ShardResolver shardResolver;
    // Nullable in plain-Mockito unit tests, same reasoning as cacheManager
    // above - always a real MeterRegistry (Actuator/Micrometer) in the
    // running app. Used only to increment the cache hit/miss counters in
    // resolve() below; every use is null-safe for the same reason.
    private final MeterRegistry meterRegistry;

    // When true, recordClick publishes to Kafka and returns immediately
    // instead of writing synchronously - see recordClick. Off by default,
    // same pattern as app.redis.enabled/app.safe-browsing.enabled: works
    // with zero extra setup until you actually want the async path.
    @Value("${app.kafka.enabled:false}")
    private boolean kafkaEnabled;

    // Whether an inconclusive Safe Browsing answer (API disabled, or the
    // call itself failed/timed out) lets a link through or blocks it. True
    // (the default) prioritizes availability - a Safe Browsing outage
    // shouldn't take link creation down with it. An actual UNSAFE match
    // from the API is blocked either way; this only governs the "we
    // couldn't check" case.
    @Value("${app.safe-browsing.fail-open:true}")
    private boolean safeBrowsingFailOpen;

    // Public reports are a signal, not proof (see ReportRequest), so
    // crossing this many auto-flags the link for review rather than
    // deleting it outright - an owner can still see and contest it.
    @Value("${app.report.auto-disable-threshold:3}")
    private int reportAutoDisableThreshold;

    /**
     * @param baseUrl     the scheme+host+port the caller actually used to
     *                    reach this server, built by the controller from the
     *                    incoming request.
     * @param currentUser the authenticated user, or null for an anonymous
     *                    request. Anonymous shortening keeps working exactly
     *                    as it always did - this parameter only attaches an
     *                    owner when one is actually logged in.
     */
    @Transactional
    public ShortenResponse shorten(ShortenRequest request, String baseUrl, User currentUser) {
        String longUrl = request.getLongUrl();

        // Refuse to shorten a URL that just points back to this service -
        // otherwise a click could redirect to another short link on this
        // same server, potentially chaining into a redirect loop. Compare
        // against baseUrl + "/" (not a bare prefix) so e.g. "8080" doesn't
        // false-positive match a longer port like "80800".
        if (baseUrl != null && longUrl.toLowerCase().startsWith(baseUrl.toLowerCase() + "/")) {
            throw new InvalidUrlException("Cannot shorten a URL that points back to this service.");
        }

        assertUrlIsSafeToShorten(longUrl);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = request.getExpiresInDays() != null
                ? now.plusDays(request.getExpiresInDays())
                : null;

        // Generated once per link, shown to the caller exactly once in the
        // response below, and only ever stored as a hash after that. This is
        // what lets someone view stats/clicks for their own link without an
        // account, while a stranger who merely has the short code cannot.
        String rawManagementKey = ManagementKeyGenerator.generate();
        String managementKeyHash = passwordEncoder.encode(rawManagementKey);

        String alias = request.getCustomAlias();

        if (alias != null && !alias.isBlank()) {
            return shortenWithCustomAlias(longUrl, alias, now, expiresAt, baseUrl, currentUser,
                    rawManagementKey, managementKeyHash);
        }
        return shortenWithGeneratedCode(longUrl, now, expiresAt, baseUrl, currentUser,
                rawManagementKey, managementKeyHash);
    }

    private ShortenResponse shortenWithCustomAlias(String longUrl, String alias, LocalDateTime now,
                                                     LocalDateTime expiresAt, String baseUrl, User currentUser,
                                                     String rawManagementKey, String managementKeyHash) {
        ShardContext.set(shardResolver.resolve(alias));
        try {
            // Pre-check first so the common case (alias free) returns a clean
            // 409 without ever hitting the database's exception path.
            if (repository.existsByShortCode(alias)) {
                throw new AliasAlreadyExistsException(alias);
            }

            UrlMapping mapping = UrlMapping.builder()
                    .longUrl(longUrl)
                    .shortCode(alias)
                    .customAlias(true)
                    .createdAt(now)
                    .expiresAt(expiresAt)
                    .clickCount(0L)
                    .user(currentUser)
                    .managementKeyHash(managementKeyHash)
                    .build();

            try {
                // Belt-and-suspenders: if two requests for the same alias race
                // each other, the pre-check above can't catch the second one -
                // the database's unique constraint on shortCode is the real
                // source of truth, and this catch turns that low-level
                // constraint violation into the same 409 a normal duplicate
                // would get, instead of the caller seeing a raw 500.
                repository.saveAndFlush(mapping);
            } catch (DataIntegrityViolationException ex) {
                throw new AliasAlreadyExistsException(alias);
            }

            return toResponse(mapping, baseUrl, rawManagementKey);
        } finally {
            ShardContext.clear();
        }
    }

    /**
     * No custom alias: generate a random code, check for a collision, and
     * persist in a single insert. This avoids the previous two-phase write
     * (insert a "tmp-..." placeholder, then update it once the id is known)
     * entirely - there's no longer any window where a row could be left
     * with an invalid placeholder code if the process died between two
     * writes, and no second round trip needed at all in the common case.
     * A collision on a random 7-character Base62 code (62^7, ~3.5 trillion
     * possibilities) is astronomically unlikely, but is still handled
     * exactly like a custom-alias collision would be: a pre-check via
     * existsByShortCode, with the database's unique constraint as the real
     * source of truth in case of a race between two concurrent requests.
     *
     * <p>Under postgres-sharded: the shard is resolved fresh from each
     * randomly-generated candidate code, per attempt - not once up front -
     * since a retry after a collision means a brand new code, which can
     * legitimately land on a different shard than the previous attempt.
     */
    private ShortenResponse shortenWithGeneratedCode(String longUrl, LocalDateTime now,
                                                       LocalDateTime expiresAt, String baseUrl, User currentUser,
                                                       String rawManagementKey, String managementKeyHash) {
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            String code = RandomCodeGenerator.generate(GENERATED_CODE_LENGTH);
            ShardContext.set(shardResolver.resolve(code));
            try {
                if (repository.existsByShortCode(code)) {
                    continue;
                }

                UrlMapping mapping = UrlMapping.builder()
                        .longUrl(longUrl)
                        .shortCode(code)
                        .customAlias(false)
                        .createdAt(now)
                        .expiresAt(expiresAt)
                        .clickCount(0L)
                        .user(currentUser)
                        .managementKeyHash(managementKeyHash)
                        .build();

                try {
                    repository.saveAndFlush(mapping);
                    return toResponse(mapping, baseUrl, rawManagementKey);
                } catch (DataIntegrityViolationException ex) {
                    // Lost a race with a concurrent request that grabbed the
                    // same code between our pre-check and this insert - retry
                    // with a fresh code rather than surfacing a raw 500.
                }
            } finally {
                ShardContext.clear();
            }
        }

        // Vanishingly unlikely to ever be reached (would require multiple
        // collisions in a row out of 3.5 trillion possibilities), but fail
        // loudly rather than silently looping forever if it somehow is.
        throw new IllegalStateException(
                "Could not generate a unique short code after " + MAX_GENERATION_ATTEMPTS + " attempts.");
    }

    @Transactional
    public String resolve(String shortCode, String clientIp) {
        ShardContext.set(shardResolver.resolve(shortCode));
        try {
            Cache cache = shortUrlCache();
            CachedShortUrl cached = cache != null ? cache.get(shortCode, CachedShortUrl.class) : null;

            if (cached != null) {
                if (cached.isExpired()) {
                    // Cached before it expired; time has simply passed since -
                    // evict and fall through to the authoritative DB check
                    // below rather than serve a redirect that's now wrong.
                    cache.evict(shortCode);
                } else {
                    // Cache hit on a link we know was ACTIVE and not expired as
                    // of when it was cached - skip the SELECT entirely and go
                    // straight to recording the click.
                    recordCacheOutcome(true);
                    recordClick(shortCode, clientIp);
                    return cached.longUrl();
                }
            }
            recordCacheOutcome(false);

            UrlMapping mapping = repository.findByShortCode(shortCode)
                    .orElseThrow(() -> new UrlNotFoundException(shortCode));

            if (mapping.isExpired()) {
                // 410 Gone (mapped in GlobalExceptionHandler) - the link did
                // exist, it's just no longer valid. Distinct from "never
                // existed" (404), which callers may want to handle differently.
                throw new UrlExpiredException(shortCode);
            }

            if (mapping.isBlocked()) {
                // 403 Forbidden - deliberately disabled (by its owner) or
                // flagged (by the Safe Browsing pipeline or enough public
                // reports). Checked on every resolve, not just at creation,
                // since a link that was safe when shortened can turn malicious
                // later (see recheckActiveLinksForThreats).
                throw new UrlBlockedException(shortCode);
            }

            // Only ever cache a verified-ACTIVE, not-yet-expired link - a
            // blocked or expired one is never cached positively, so there's
            // nothing to evict for those cases beyond what disable/report/the
            // recheck job already do on their own paths.
            if (cache != null) {
                cache.put(shortCode, new CachedShortUrl(mapping.getLongUrl(), mapping.getExpiresAt()));
            }

            recordClick(shortCode, clientIp);
            return mapping.getLongUrl();
        } finally {
            // Under postgres-sharded, every code path above - cache hit,
            // 410, 403, or a normal resolve - must still release this, or
            // the next request handled by this pooled thread would
            // silently inherit this shortCode's shard.
            ShardContext.clear();
        }
    }

    // Bulk UPDATE + a plain insert, neither of which requires the mapping
    // row to already be loaded - see UrlMappingRepository.incrementClickCount.
    // This is what makes the cache-hit path above genuinely skip the
    // url_mapping table rather than just moving the same SELECT earlier.
    //
    // When app.kafka.enabled=true, this does neither write itself - it
    // publishes to Kafka and returns, so the redirect response doesn't wait
    // on any DB I/O at all. ClickEventConsumer does the actual persistence,
    // asynchronously, on its own consumer thread.
    private void recordClick(String shortCode, String clientIp) {
        LocalDateTime now = LocalDateTime.now();

        if (kafkaEnabled) {
            clickEventPublisher.publish(shortCode, clientIp, now);
            return;
        }

        repository.incrementClickCount(shortCode, now);
        clickEventRepository.save(UrlClickEvent.builder()
                .shortCode(shortCode)
                .clickedAt(now)
                .ipAddress(clientIp)
                .build());
    }

    private Cache shortUrlCache() {
        return cacheManager != null ? cacheManager.getCache(CacheConfig.SHORT_URL_CACHE) : null;
    }

    // Tagged by outcome (hit/miss) rather than two separate counter names,
    // so both show up under one metric name in Prometheus/Grafana and a
    // hit-rate query is a single ratio expression instead of two lookups.
    private void recordCacheOutcome(boolean hit) {
        if (meterRegistry != null) {
            meterRegistry.counter("url_shortener.redirect.cache", "result", hit ? "hit" : "miss").increment();
        }
    }

    private void evictFromCache(String shortCode) {
        Cache cache = shortUrlCache();
        if (cache != null) {
            cache.evict(shortCode);
        }
    }

    /**
     * @param managementKey the raw key the caller supplied (via the
     *                      X-Management-Key header), or null if they
     *                      didn't supply one.
     * @param currentUser   the authenticated user, or null if anonymous.
     *                      Either a matching key OR being the link's owner
     *                      grants access - see assertCanViewAnalytics.
     */
    @Transactional(readOnly = true)
    public UrlStatsResponse getStats(String shortCode, String managementKey, User currentUser) {
        ShardContext.set(shardResolver.resolve(shortCode));
        try {
            UrlMapping mapping = repository.findByShortCode(shortCode)
                    .orElseThrow(() -> new UrlNotFoundException(shortCode));

            assertCanViewAnalytics(mapping, managementKey, currentUser);

            return UrlStatsResponse.builder()
                    .shortCode(mapping.getShortCode())
                    .longUrl(mapping.getLongUrl())
                    .clickCount(mapping.getClickCount())
                    .createdAt(mapping.getCreatedAt())
                    .expiresAt(mapping.getExpiresAt())
                    .lastAccessedAt(mapping.getLastAccessedAt())
                    .expired(mapping.isExpired())
                    .status(statusLabel(mapping))
                    .statusReason(mapping.getStatusReason())
                    .reportCount(mapping.getReportCount())
                    .build();
        } finally {
            ShardContext.clear();
        }
    }

    /**
     * Paginated click history for a short code, newest first. Uses Spring
     * Data's Pageable so the response includes page/size/total metadata
     * instead of ever loading a link's entire click history into memory.
     * Gated by the same management-key-or-ownership check as getStats.
     */
    @Transactional(readOnly = true)
    public Page<ClickResponse> getClicks(String shortCode, Pageable pageable, String managementKey, User currentUser) {
        ShardContext.set(shardResolver.resolve(shortCode));
        try {
            UrlMapping mapping = repository.findByShortCode(shortCode)
                    .orElseThrow(() -> new UrlNotFoundException(shortCode));

            assertCanViewAnalytics(mapping, managementKey, currentUser);

            return clickEventRepository.findByShortCodeOrderByClickedAtDesc(shortCode, pageable)
                    .map(event -> ClickResponse.builder()
                            .clickedAt(event.getClickedAt())
                            .ipAddress(event.getIpAddress())
                            .build());
        } finally {
            ShardContext.clear();
        }
    }

    /**
     * Grants access to a link's stats/clicks if EITHER:
     *  - the requester is authenticated and owns the link, or
     *  - the requester supplied the correct management key for it.
     * Anyone else - including someone who merely knows/guessed the short
     * code - is rejected with 403, even though the short code itself always
     * still works for the actual redirect (see UrlController.redirect()).
     */
    private void assertCanViewAnalytics(UrlMapping mapping, String managementKey, User currentUser) {
        boolean isOwner = currentUser != null
                && mapping.getUser() != null
                && mapping.getUser().getId().equals(currentUser.getId());

        boolean hasValidKey = managementKey != null
                && !managementKey.isBlank()
                && mapping.getManagementKeyHash() != null
                && passwordEncoder.matches(managementKey, mapping.getManagementKeyHash());

        if (!isOwner && !hasValidKey) {
            throw new InvalidManagementKeyException();
        }
    }

    /**
     * The authenticated user's permanent URL history ("My Dispatches"),
     * paginated. Queried directly by owner id at the database level - never
     * "fetch everything, filter in the service/frontend", which would risk
     * leaking other users' URLs through a bug further up the stack, and
     * would load a user's entire history into memory regardless of how
     * large it's grown.
     *
     * <p>KNOWN GAP under postgres-sharded: this queries by userId, not
     * shortCode, so there's no single shard to resolve it to - a user's
     * links are scattered across every shard by design (see ShardResolver).
     * This method doesn't set ShardContext at all, so it always reads
     * whichever shard is the routing datasource's default (shard 0) and
     * silently misses that user's links on every other shard. A correct
     * fix needs either scatter-gather across all shards or a separate
     * user_id -> shard-aware index table; neither is implemented here.
     */
    @Transactional(readOnly = true)
    public Page<UrlSummaryResponse> getMyUrls(User user, String baseUrl, Pageable pageable) {
        return repository.findByUser_IdOrderByCreatedAtDesc(user.getId(), pageable)
                .map(mapping -> UrlSummaryResponse.builder()
                        .shortCode(mapping.getShortCode())
                        .shortUrl(baseUrl + "/" + mapping.getShortCode())
                        .longUrl(mapping.getLongUrl())
                        .createdAt(mapping.getCreatedAt())
                        .expiresAt(mapping.getExpiresAt())
                        .clickCount(mapping.getClickCount())
                        .status(statusLabel(mapping))
                        .statusReason(mapping.getStatusReason())
                        .build());
    }

    // Expiry (time-based, self-service) is checked ahead of status
    // (deliberate block, from either the owner or the abuse pipeline) since
    // "Expired" is the more useful thing to show an owner for a link that's
    // both - blocking a link that's about to expire anyway isn't worth
    // surfacing separately.
    private String statusLabel(UrlMapping mapping) {
        if (mapping.isExpired()) {
            return "Expired";
        }
        return switch (mapping.getStatus()) {
            case ACTIVE -> "Active";
            case FLAGGED -> "Flagged";
            case DISABLED -> "Disabled";
        };
    }

    /**
     * Dashboard summary-card counts for the current user, computed as
     * COUNT/SUM queries rather than by summing a fully-loaded list - see
     * UrlDashboardSummaryResponse.
     *
     * <p>Same KNOWN GAP as getMyUrls under postgres-sharded - userId-keyed,
     * not shortCode-keyed, so this only ever sees shard 0.
     */
    @Transactional(readOnly = true)
    public UrlDashboardSummaryResponse getMyUrlsSummary(User user) {
        LocalDateTime now = LocalDateTime.now();
        long total = repository.countByUser_Id(user.getId());
        long expired = repository.countByUser_IdAndExpiresAtBefore(user.getId(), now);
        // Not-yet-expired links the owner disabled or that got flagged - see
        // the repository method doc for why expired+blocked links are
        // excluded here (they're already counted under "expired").
        long blocked = repository.countByUser_IdAndStatusNotAndNotExpired(user.getId(), UrlStatus.ACTIVE, now);
        long totalClicks = repository.sumClickCountByUser_Id(user.getId());

        return UrlDashboardSummaryResponse.builder()
                .total(total)
                .active(total - expired - blocked)
                .expired(expired)
                .blocked(blocked)
                .totalClicks(totalClicks)
                .build();
    }

    /**
     * Deletes a URL, but only if the requesting user actually owns it.
     * Anonymous URLs (owner == null) can't be deleted through this
     * endpoint by anyone, since they aren't associated with any account.
     */
    @Transactional
    public void deleteUrl(String shortCode, User user) {
        ShardContext.set(shardResolver.resolve(shortCode));
        try {
            UrlMapping mapping = repository.findByShortCode(shortCode)
                    .orElseThrow(() -> new UrlNotFoundException(shortCode));

            if (mapping.getUser() == null || !mapping.getUser().getId().equals(user.getId())) {
                throw new ForbiddenException("You do not have permission to delete this URL.");
            }

            repository.delete(mapping);
            evictFromCache(shortCode);
        } finally {
            ShardContext.clear();
        }
    }

    // Runs once an hour and deletes links whose expiry date has passed, so
    // the table doesn't grow forever with dead links.
    //
    // KNOWN GAP under postgres-sharded: findByExpiresAtBefore queries
    // whichever single shard ShardContext currently points to (shard 0, by
    // ShardRoutingDataSource's fallback, since nothing sets a shard here -
    // there's no single shortCode to hash). A real sharded deployment needs
    // this job to run once per shard - e.g. iterate app.sharding.num-shards
    // and set ShardContext to each in turn - which isn't implemented here.
    // Left as a visible gap rather than silently only cleaning shard 0.
    @Scheduled(fixedRate = 60 * 60 * 1000)
    @Transactional
    public void purgeExpiredLinks() {
        repository.findByExpiresAtBefore(LocalDateTime.now())
                .forEach(mapping -> {
                    repository.delete(mapping);
                    evictFromCache(mapping.getShortCode());
                });
    }

    /**
     * Blocks shortening a URL that Safe Browsing has an actual match for.
     * An UNSAFE result always blocks, regardless of fail-open config - that
     * setting only decides what happens when we couldn't get a real answer
     * at all (disabled, or the API call failed/timed out).
     */
    private void assertUrlIsSafeToShorten(String longUrl) {
        SafeBrowsingResult result = safeBrowsingClient.check(longUrl);

        if (result.isUnsafe()) {
            throw new UnsafeUrlException(
                    "This URL was flagged as unsafe (" + String.join(", ", result.threatTypes())
                            + ") and can't be shortened.");
        }

        if (!result.isConclusive() && !safeBrowsingFailOpen) {
            throw new UnsafeUrlException(
                    "Could not verify this URL is safe right now - please try again shortly.");
        }
    }

    /**
     * Public "report this link" endpoint - deliberately open to anyone (not
     * just the owner), since the whole point is letting someone who was
     * sent a malicious short link flag it even though they have no account
     * and no management key. Rate-limited per IP at the filter level (see
     * RateLimitFilter/app.report-rate-limit) so it can't itself be used to
     * mass-disable a competitor's links by spamming reports from one
     * source; crossing reportAutoDisableThreshold flags the link rather
     * than deleting it, so a false-positive is always recoverable by an
     * admin/owner re-enabling it.
     */
    @Transactional
    public ReportResponse report(String shortCode) {
        ShardContext.set(shardResolver.resolve(shortCode));
        try {
            UrlMapping mapping = repository.findByShortCode(shortCode)
                    .orElseThrow(() -> new UrlNotFoundException(shortCode));

            mapping.setReportCount(mapping.getReportCount() + 1);

            boolean autoFlagged = false;

            // Trigger malicious threat check on report if Safe Browsing is configured
            if (safeBrowsingClient != null && safeBrowsingClient.isEnabled()) {
                SafeBrowsingResult result = safeBrowsingClient.check(mapping.getLongUrl());
                if (result.isUnsafe()) {
                    mapping.setStatus(UrlStatus.FLAGGED);
                    mapping.setStatusReason("Blocked: threat detected by Safe Browsing (" + String.join(", ", result.threatTypes()) + ")");
                    autoFlagged = true;
                    evictFromCache(shortCode);
                }
            }

            if (!autoFlagged && mapping.getStatus() == UrlStatus.ACTIVE && mapping.getReportCount() >= reportAutoDisableThreshold) {
                mapping.setStatus(UrlStatus.FLAGGED);
                mapping.setStatusReason("Auto-flagged after " + mapping.getReportCount() + " abuse reports");
                autoFlagged = true;
                evictFromCache(shortCode);
            }

            repository.save(mapping);

            return ReportResponse.builder()
                    .shortCode(shortCode)
                    .reportCount(mapping.getReportCount())
                    .autoFlagged(autoFlagged)
                    .build();
        } finally {
            ShardContext.clear();
        }
    }

    /**
     * Owner (or management-key holder) turning their own link off/back on.
     * Uses the same access check as stats/clicks (assertCanViewAnalytics) -
     * anyone who can see a link's analytics can also control whether it
     * resolves.
     */
    @Transactional
    public void setLinkEnabled(String shortCode, boolean enabled, String managementKey, User currentUser) {
        ShardContext.set(shardResolver.resolve(shortCode));
        try {
            UrlMapping mapping = repository.findByShortCode(shortCode)
                    .orElseThrow(() -> new UrlNotFoundException(shortCode));

            assertCanViewAnalytics(mapping, managementKey, currentUser);

            if (enabled) {
                mapping.setStatus(UrlStatus.ACTIVE);
                mapping.setStatusReason(null);
            } else {
                mapping.setStatus(UrlStatus.DISABLED);
                mapping.setStatusReason("Disabled by owner");
            }

            repository.save(mapping);
            // Unconditional for both branches: disabling must evict so a
            // still-cached ACTIVE entry doesn't keep serving redirects past
            // the block (resolve() checks the cache before it checks status).
            // Re-enabling evicts too, mostly as cheap insurance against any
            // stale entry - a blocked link is never positively cached in the
            // first place, so there's normally nothing to remove there.
            evictFromCache(shortCode);
        } finally {
            ShardContext.clear();
        }
    }

    /**
     * Re-checks every currently-ACTIVE link's destination against Safe
     * Browsing, in batches sized to the API's own per-request cap. Exists
     * because a destination can turn malicious well after its short link
     * was created - the check in {@link #assertUrlIsSafeToShorten} only
     * covers the moment of creation. Links whose destination now matches a
     * threat move to FLAGGED, which blocks the redirect the same way
     * DISABLED does (see resolve()) without touching anything the owner
     * set themselves.
     *
     * <p>No-op (checkBatch returns immediately) when Safe Browsing isn't
     * configured, so this is safe to leave scheduled even with the feature
     * off.
     *
     * <p>Same KNOWN GAP as purgeExpiredLinks under postgres-sharded - this
     * only ever scans shard 0 (ShardRoutingDataSource's fallback), since
     * there's no single shortCode driving this job either. Not fixed here.
     */
    @Scheduled(fixedRateString = "${app.safe-browsing.recheck-interval-ms:21600000}") // default: 6 hours
    @Transactional
    public void recheckActiveLinksForThreats() {
        if (!safeBrowsingClient.isEnabled()) {
            return;
        }

        Pageable pageSize = PageRequest.of(0, SafeBrowsingClient.MAX_URLS_PER_REQUEST);
        int flaggedCount = 0;
        long lastSeenId = 0L;

        List<UrlMapping> links = repository.findByStatusAndIdGreaterThanOrderByIdAsc(
                UrlStatus.ACTIVE, lastSeenId, pageSize);

        while (!links.isEmpty()) {
            Map<String, List<String>> matches = safeBrowsingClient.checkBatch(
                    links.stream().map(UrlMapping::getLongUrl).distinct().collect(Collectors.toList()));

            LocalDateTime now = LocalDateTime.now();
            for (UrlMapping mapping : links) {
                mapping.setLastSafetyCheckAt(now);
                List<String> threats = matches.get(mapping.getLongUrl());
                if (threats != null && !threats.isEmpty()) {
                    mapping.setStatus(UrlStatus.FLAGGED);
                    mapping.setStatusReason("Flagged by scheduled Safe Browsing re-check ("
                            + String.join(", ", threats) + ")");
                    flaggedCount++;
                    evictFromCache(mapping.getShortCode());
                }
            }
            repository.saveAll(links);

            lastSeenId = links.get(links.size() - 1).getId();
            links = links.size() < SafeBrowsingClient.MAX_URLS_PER_REQUEST
                    ? List.of() // short batch means this was the last page
                    : repository.findByStatusAndIdGreaterThanOrderByIdAsc(UrlStatus.ACTIVE, lastSeenId, pageSize);
        }

        if (flaggedCount > 0) {
            log.info("Safe Browsing re-check flagged {} link(s) this run.", flaggedCount);
        }
    }

    private ShortenResponse toResponse(UrlMapping mapping, String baseUrl, String rawManagementKey) {
        return ShortenResponse.builder()
                .shortCode(mapping.getShortCode())
                .shortUrl(baseUrl + "/" + mapping.getShortCode())
                .longUrl(mapping.getLongUrl())
                .createdAt(mapping.getCreatedAt())
                .expiresAt(mapping.getExpiresAt())
                .managementKey(rawManagementKey)
                .build();
    }
}
