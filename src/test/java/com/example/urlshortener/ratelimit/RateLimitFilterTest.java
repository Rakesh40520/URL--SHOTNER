package com.example.urlshortener.ratelimit;

import com.example.urlshortener.util.ClientIpResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    @Mock
    private RateLimiter shortenRateLimiter;
    @Mock
    private RateLimiter authRateLimiter;
    @Mock
    private RateLimiter reportRateLimiter;
    @Mock
    private ClientIpResolver clientIpResolver;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private FilterChain filterChain;

    // A real registry (not a mock) - the counter interactions below are
    // exactly the kind of thing worth verifying against real Micrometer
    // behavior (tag matching, increment semantics) rather than a mock.
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter(shortenRateLimiter, authRateLimiter, reportRateLimiter,
                clientIpResolver, new ObjectMapper(), meterRegistry);
        lenient().when(clientIpResolver.resolve(request)).thenReturn("203.0.113.5");
    }

    @Test
    void allowedRequest_passesThroughToFilterChain_andWritesNoResponse() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/urls");
        when(shortenRateLimiter.tryConsume("203.0.113.5")).thenReturn(true);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(response, never()).setStatus(anyInt());
    }

    @Test
    void deniedShortenRequest_returns429_andIncrementsShortenRejectedCounter() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/urls");
        when(shortenRateLimiter.tryConsume("203.0.113.5")).thenReturn(false);
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(429);
        verify(filterChain, never()).doFilter(request, response);
        assertEquals(1.0, meterRegistry.counter("url_shortener.ratelimit.rejected", "limiter", "shorten").count());
    }

    @Test
    void deniedAuthRequest_incrementsAuthRejectedCounter() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/auth/login");
        when(authRateLimiter.tryConsume("203.0.113.5")).thenReturn(false);
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        filter.doFilterInternal(request, response, filterChain);

        assertEquals(1.0, meterRegistry.counter("url_shortener.ratelimit.rejected", "limiter", "auth").count());
    }

    @Test
    void deniedReportRequest_incrementsReportRejectedCounter() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/urls/abc1234/report");
        when(reportRateLimiter.tryConsume("203.0.113.5")).thenReturn(false);
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        filter.doFilterInternal(request, response, filterChain);

        assertEquals(1.0, meterRegistry.counter("url_shortener.ratelimit.rejected", "limiter", "report").count());
    }

    @Test
    void nonPostRequest_isNeverRateLimited() throws Exception {
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/v1/urls");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(shortenRateLimiter, authRateLimiter, reportRateLimiter);
    }

    @Test
    void postToUnmatchedPath_passesThroughWithoutConsultingAnyLimiter() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/urls/abc1234/stats");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(shortenRateLimiter, authRateLimiter, reportRateLimiter);
    }
}
