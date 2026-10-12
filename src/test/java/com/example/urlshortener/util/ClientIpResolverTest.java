package com.example.urlshortener.util;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClientIpResolverTest {

    private ClientIpResolver resolverWithTrustProxyHeaders(boolean trustProxyHeaders) {
        ClientIpResolver resolver = new ClientIpResolver();
        ReflectionTestUtils.setField(resolver, "trustProxyHeaders", trustProxyHeaders);
        return resolver;
    }

    @Test
    void byDefaultIgnoresXForwardedForAndUsesRemoteAddr() {
        // This is the fix for the rate-limit bypass: with trustProxyHeaders
        // off (the default), a caller sending a spoofed/rotating
        // X-Forwarded-For header must not be able to change what IP is
        // used as the rate-limit key.
        ClientIpResolver resolver = resolverWithTrustProxyHeaders(false);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("9.9.9.9");
        when(request.getRemoteAddr()).thenReturn("203.0.113.5");

        assertEquals("203.0.113.5", resolver.resolve(request));
    }

    @Test
    void whenTrustProxyHeadersEnabledUsesFirstEntryInXForwardedFor() {
        ClientIpResolver resolver = resolverWithTrustProxyHeaders(true);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.7, 10.0.0.1");
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");

        assertEquals("198.51.100.7", resolver.resolve(request));
    }

    @Test
    void whenTrustProxyHeadersEnabledButHeaderAbsentFallsBackToRemoteAddr() {
        ClientIpResolver resolver = resolverWithTrustProxyHeaders(true);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("203.0.113.5");

        assertEquals("203.0.113.5", resolver.resolve(request));
    }

    @Test
    void resolveForAnalytics_usesForwardedHeaderOnlyWhenGeoFlagIsOn_andNeverChangesRateLimitIp() {
        ClientIpResolver resolver = resolverWithTrustProxyHeaders(false);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.7, 10.0.0.2");
        when(request.getRemoteAddr()).thenReturn("10.0.0.2");

        // flag off (default): analytics IP == socket IP
        assertEquals("10.0.0.2", resolver.resolveForAnalytics(request));

        ReflectionTestUtils.setField(resolver, "geoTrustForwardedFor", true);
        assertEquals("198.51.100.7", resolver.resolveForAnalytics(request));
        // ...but the rate-limiter IP is untouched
        assertEquals("10.0.0.2", resolver.resolve(request));
    }
}
