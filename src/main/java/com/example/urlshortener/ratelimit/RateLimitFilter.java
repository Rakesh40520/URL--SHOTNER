package com.example.urlshortener.ratelimit;

import com.example.urlshortener.util.ClientIpResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rate-limits sensitive POST endpoints per client IP:
 *  - POST /api/v1/urls (link creation) - stops scripted abuse of shortening
 *  - POST /api/v1/auth/login and /register - stops credential-stuffing /
 *    brute-force and mass account creation
 *  - POST /api/v1/urls/{shortCode}/report - stops one caller from spamming
 *    reports to auto-flag someone else's link (it takes no auth/key, so
 *    this is its only protection)
 * Each group has its own RateLimiter (see RateLimitConfig) with its own
 * budget, so hitting the limit on one doesn't affect the other. Every other
 * route (redirects, stats, docs, the frontend) passes through unthrottled.
 *
 * Registered automatically by Spring Boot because it's a @Component that
 * implements Filter - no manual FilterRegistrationBean needed.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String SHORTEN_PATH = "/api/v1/urls";
    private static final Set<String> AUTH_PATHS = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/register"
    );
    // Matches /api/v1/urls/{shortCode}/report for any short code shape the
    // redirect route itself accepts ([a-zA-Z0-9_-]{3,20}) - kept in sync
    // with UrlController.redirect()'s path pattern deliberately.
    private static final Pattern REPORT_PATH = Pattern.compile("^/api/v1/urls/[a-zA-Z0-9_-]{3,20}/report$");

    private final RateLimiter shortenRateLimiter;
    private final RateLimiter authRateLimiter;
    private final RateLimiter reportRateLimiter;
    private final ClientIpResolver clientIpResolver;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public RateLimitFilter(@Qualifier("shortenRateLimiter") RateLimiter shortenRateLimiter,
                            @Qualifier("authRateLimiter") RateLimiter authRateLimiter,
                            @Qualifier("reportRateLimiter") RateLimiter reportRateLimiter,
                            ClientIpResolver clientIpResolver,
                            ObjectMapper objectMapper,
                            MeterRegistry meterRegistry) {
        this.shortenRateLimiter = shortenRateLimiter;
        this.authRateLimiter = authRateLimiter;
        this.reportRateLimiter = reportRateLimiter;
        this.clientIpResolver = clientIpResolver;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {

        if ("POST".equalsIgnoreCase(request.getMethod())) {
            String path = request.getRequestURI();
            RateLimiter limiter = rateLimiterFor(path);

            if (limiter != null) {
                String clientIp = clientIpResolver.resolve(request);
                if (!limiter.tryConsume(clientIp)) {
                    // Tagged by which budget was hit (shorten/auth/report) so
                    // a dashboard can tell "someone's hammering login" apart
                    // from "someone's hammering the shorten endpoint" instead
                    // of one undifferentiated rejection count.
                    meterRegistry.counter("url_shortener.ratelimit.rejected", "limiter", limiterName(path)).increment();
                    writeTooManyRequests(response);
                    return;
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    private RateLimiter rateLimiterFor(String path) {
        if (SHORTEN_PATH.equals(path)) {
            return shortenRateLimiter;
        }
        if (AUTH_PATHS.contains(path)) {
            return authRateLimiter;
        }
        if (REPORT_PATH.matcher(path).matches()) {
            return reportRateLimiter;
        }
        return null;
    }

    private String limiterName(String path) {
        if (SHORTEN_PATH.equals(path)) {
            return "shorten";
        }
        if (AUTH_PATHS.contains(path)) {
            return "auth";
        }
        return "report";
    }

    private void writeTooManyRequests(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", HttpStatus.TOO_MANY_REQUESTS.value());
        body.put("error", HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase());
        body.put("message", "Too many requests - please slow down and try again shortly.");

        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
