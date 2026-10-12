package com.example.urlshortener.controller;

import com.example.urlshortener.dto.ClickAnalyticsResponse;
import com.example.urlshortener.dto.ClickResponse;
import com.example.urlshortener.dto.ClickSource;
import com.example.urlshortener.dto.ReportRequest;
import com.example.urlshortener.dto.ReportResponse;
import com.example.urlshortener.dto.ShortenRequest;
import com.example.urlshortener.dto.ShortenResponse;
import com.example.urlshortener.dto.UrlDashboardSummaryResponse;
import com.example.urlshortener.dto.UrlStatsResponse;
import com.example.urlshortener.dto.UrlSummaryResponse;
import com.example.urlshortener.entity.User;
import com.example.urlshortener.service.SubscriptionService;
import com.example.urlshortener.service.UrlShortenerService;
import com.example.urlshortener.util.ClientIpResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

@RestController
@RequiredArgsConstructor
@Tag(name = "URL Shortener", description = "Create, resolve, and inspect short URLs")
public class UrlController {

    private final UrlShortenerService service;
    private final SubscriptionService subscriptionService;
    private final ClientIpResolver clientIpResolver;

    @Operation(summary = "Create a short URL for a given long URL (works anonymously or logged in)")
    @PostMapping("/api/v1/urls")
    public ResponseEntity<ShortenResponse> shorten(@Valid @RequestBody ShortenRequest request,
                                                     HttpServletRequest httpRequest,
                                                     @AuthenticationPrincipal User currentUser) {
        // Build the base URL from whatever host/IP/port the request actually
        // came in on (localhost, a LAN IP, a real domain behind a proxy...)
        // instead of a hardcoded value, so the short link always matches how
        // the caller reached the server.
        String baseUrl = ServletUriComponentsBuilder.fromRequestUri(httpRequest)
                .replacePath(null)
                .replaceQuery(null)
                .build()
                .toUriString();

        // currentUser is null for an anonymous caller (this endpoint is
        // permitAll - see SecurityConfig) and the User resolved from the
        // JWT for a logged-in one. Either way the service decides what to
        // do with it; the controller never invents ownership on its own.
        // Enforce the signed-in user's subscription plan first (quota, custom
        // alias, max expiry). No-op for anonymous callers.
        subscriptionService.applyPlanRules(request, currentUser);

        ShortenResponse response = service.shorten(request, baseUrl, currentUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Get the current user's permanent URL history (\"My Dispatches\"), paginated")
    @GetMapping("/api/v1/urls/my")
    public ResponseEntity<Page<UrlSummaryResponse>> myUrls(HttpServletRequest httpRequest,
                                                             @AuthenticationPrincipal User currentUser,
                                                             @PageableDefault(size = 20) Pageable pageable) {
        // No null-check needed here: SecurityConfig requires authentication
        // for this route, so if we reach this method body, Spring Security
        // has already guaranteed currentUser is a real, logged-in user.
        String baseUrl = ServletUriComponentsBuilder.fromRequestUri(httpRequest)
                .replacePath(null)
                .replaceQuery(null)
                .build()
                .toUriString();

        return ResponseEntity.ok(service.getMyUrls(currentUser, baseUrl, pageable));
    }

    @Operation(summary = "Get total/active/expired link counts and total clicks for the current user's dashboard")
    @GetMapping("/api/v1/urls/my/summary")
    public ResponseEntity<UrlDashboardSummaryResponse> myUrlsSummary(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(service.getMyUrlsSummary(currentUser));
    }

    @Operation(summary = "Delete one of the current user's own URLs")
    @DeleteMapping("/api/v1/urls/{shortCode}")
    public ResponseEntity<Void> deleteUrl(@PathVariable String shortCode,
                                           @AuthenticationPrincipal User currentUser) {
        service.deleteUrl(shortCode, currentUser);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Get click-count/expiry metadata for a short code (requires the link's management key via the X-Management-Key header, or ownership)")
    @GetMapping("/api/v1/urls/{shortCode}/stats")
    public ResponseEntity<UrlStatsResponse> stats(@PathVariable String shortCode,
                                                    @RequestHeader(name = "X-Management-Key", required = false) String managementKey,
                                                    @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(service.getStats(shortCode, managementKey, currentUser));
    }

    @Operation(summary = "Get paginated click history for a short code (requires the link's management key via the X-Management-Key header, or ownership)")
    @GetMapping("/api/v1/urls/{shortCode}/clicks")
    public ResponseEntity<Page<ClickResponse>> clicks(@PathVariable String shortCode,
                                                        @RequestHeader(name = "X-Management-Key", required = false) String managementKey,
                                                        @AuthenticationPrincipal User currentUser,
                                                        @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(service.getClicks(shortCode, pageable, managementKey, currentUser));
    }

    @Operation(summary = "Where a link's clicks come from (country/city) and who sent them (referrer, ?ref= tag) - requires management key or ownership")
    @GetMapping("/api/v1/urls/{shortCode}/analytics")
    public ResponseEntity<ClickAnalyticsResponse> analytics(@PathVariable String shortCode,
                                                              @RequestHeader(name = "X-Management-Key", required = false) String managementKey,
                                                              @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(service.getClickAnalytics(shortCode, managementKey, currentUser));
    }

    @Operation(summary = "Report a short URL as abusive/malicious (no account needed)")
    @PostMapping("/api/v1/urls/{shortCode}/report")
    public ResponseEntity<ReportResponse> report(@PathVariable String shortCode,
                                                   @RequestBody(required = false) ReportRequest request) {
        // request/its reason isn't used yet beyond validation - it's not
        // persisted anywhere a moderator can read it (there's no moderation
        // UI in this project yet). Kept on the DTO/API surface now so
        // adding storage for it later isn't a breaking API change.
        return ResponseEntity.ok(service.report(shortCode));
    }

    @Operation(summary = "Disable one of the current user's own URLs (stops it from resolving, doesn't delete it)")
    @PostMapping("/api/v1/urls/{shortCode}/disable")
    public ResponseEntity<Void> disable(@PathVariable String shortCode,
                                         @RequestHeader(name = "X-Management-Key", required = false) String managementKey,
                                         @AuthenticationPrincipal User currentUser) {
        service.setLinkEnabled(shortCode, false, managementKey, currentUser);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Re-enable a previously disabled/flagged URL owned by the current user")
    @PostMapping("/api/v1/urls/{shortCode}/enable")
    public ResponseEntity<Void> enable(@PathVariable String shortCode,
                                        @RequestHeader(name = "X-Management-Key", required = false) String managementKey,
                                        @AuthenticationPrincipal User currentUser) {
        service.setLinkEnabled(shortCode, true, managementKey, currentUser);
        return ResponseEntity.noContent().build();
    }

    /**
     * Redirects a short code to its original long URL. Always public -
     * see SecurityConfig, this route is never behind auth.
     *
     * <p>Uses 302 Found rather than a permanent redirect, deliberately:
     * <ul>
     *   <li><b>301 Moved Permanently / 308 Permanent Redirect</b> tell
     *       browsers (and search engines) to cache the mapping and skip
     *       hitting our server on future visits. That would break click
     *       analytics and mean an expired or edited link couldn't ever
     *       change behavior for a browser that already cached the 301.</li>
     *   <li><b>302 Found / 307 Temporary Redirect</b> are not cached that
     *       way - every click still reaches this server, which is what we
     *       want since we track clicks and support expiry. 302 also
     *       preserves the original HTTP method loosely (older semantics
     *       allow method change on redirect); 307 strictly preserves the
     *       method and body, which doesn't matter here since this is
     *       always a GET, so plain 302 is the conventional choice for URL
     *       shorteners.</li>
     * </ul>
     */
    @Operation(summary = "Redirect a short code to its original long URL")
    @GetMapping("/{shortCode:[a-zA-Z0-9_-]{3,20}}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode,
                                          @RequestParam(name = "ref", required = false) String ref,
                                          HttpServletRequest httpRequest) {
        String clientIp = clientIpResolver.resolveForAnalytics(httpRequest);
        // Who sent this click: the referring website (host only) and/or an owner-set
        // ?ref=name tag. Both are optional and sanitized - see ClickSource.
        ClickSource source = ClickSource.from(httpRequest.getHeader("Referer"), ref, httpRequest.getServerName());
        String longUrl = service.resolve(shortCode, clientIp, source);

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(longUrl))
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .build();
    }
}
