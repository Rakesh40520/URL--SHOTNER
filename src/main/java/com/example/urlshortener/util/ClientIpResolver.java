package com.example.urlshortener.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves the "real" client IP for a request - used both by RateLimiter
 * (per-IP throttling) and when recording a click's ipAddress.
 *
 * <p>By default this trusts only {@code request.getRemoteAddr()}.
 * X-Forwarded-For is consulted ONLY when {@code app.trust-proxy-headers} is
 * explicitly set to true, which should happen only when this app is
 * actually deployed behind a reverse proxy/load balancer that overwrites
 * (rather than passes through) that header on every inbound connection.
 *
 * <p>Trusting X-Forwarded-For unconditionally - as a client-supplied header
 * - would let any caller set an arbitrary value and get a fresh rate-limit
 * bucket on every single request (a different spoofed IP each time),
 * defeating RateLimiter entirely. It would equally let a caller frame
 * another IP for their own clicks in click history. Only enable the flag
 * once you've verified your edge/proxy layer strips any client-sent
 * X-Forwarded-For before setting its own.
 */
@Component
public class ClientIpResolver {

    @Value("${app.trust-proxy-headers:false}")
    private boolean trustProxyHeaders;

    // Separate, narrower switch used ONLY for recording where a click came from
    // (see resolveForAnalytics). Behind a platform proxy like Render, the socket
    // address is the proxy's private IP, so location would always be "unknown".
    // Unlike trust-proxy-headers, a spoofed value here can only mislabel that one
    // click's location - it can't be used to dodge the rate limiter.
    @Value("${app.geo.trust-forwarded-for:false}")
    private boolean geoTrustForwardedFor;

    /**
     * IP to store on a click and geolocate. Same as resolve() unless
     * app.geo.trust-forwarded-for is true, in which case the first
     * X-Forwarded-For entry is used. Rate limiting always uses resolve().
     */
    public String resolveForAnalytics(HttpServletRequest request) {
        if (geoTrustForwardedFor && !trustProxyHeaders) {
            String forwardedFor = request.getHeader("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                return forwardedFor.split(",")[0].trim();
            }
        }
        return resolve(request);
    }

    public String resolve(HttpServletRequest request) {
        if (trustProxyHeaders) {
            String forwardedFor = request.getHeader("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                // X-Forwarded-For can be a comma-separated chain of proxies;
                // the first entry is the original client - trustworthy here
                // only because trustProxyHeaders being true means we trust
                // our own edge to have set/appended this correctly.
                return forwardedFor.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
