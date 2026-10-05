package com.example.urlshortener.safebrowsing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers what's testable here without a live network call or a Spring
 * context (which would be needed to exercise the actual @CircuitBreaker
 * AOP behavior - resilience4j's aspect only applies to calls made through
 * a Spring proxy, so a plain `new SafeBrowsingClient(...)` in a unit test
 * never goes through it regardless of what's annotated).
 *
 * <p>What IS covered: the disabled/short-circuit paths that never attempt
 * a network call, and the fallback methods themselves - invoked directly
 * via reflection, since they're private and that's exactly what
 * resilience4j calls when check()/checkBatch() throw or the circuit is
 * open. Testing them directly verifies the actual fallback behavior
 * without needing the breaker's state machine to produce it.
 */
class SafeBrowsingClientTest {

    private final SafeBrowsingClient client = new SafeBrowsingClient(new ObjectMapper());

    @Test
    void isEnabled_isFalseByDefault() {
        // @Value fields aren't populated outside a Spring context, so
        // `enabled` defaults to false (Java's default for boolean) - same
        // as the feature being off in application.yml.
        assertFalse(client.isEnabled());
    }

    @Test
    void check_whenDisabled_returnsDisabled_withoutAttemptingANetworkCall() {
        SafeBrowsingResult result = client.check("https://example.com");

        assertEquals(SafeBrowsingResult.Status.DISABLED, result.status());
        assertFalse(result.isUnsafe());
        assertFalse(result.isConclusive());
    }

    @Test
    void checkBatch_whenDisabled_returnsEmptyMap() {
        assertTrue(client.checkBatch(List.of("https://example.com")).isEmpty());
    }

    @Test
    void checkBatch_withEmptyUrlList_returnsEmptyMap_evenWhenEnabled() {
        // Flip enabled on via reflection (no Spring context to do it via
        // @Value) specifically to prove the empty-list short-circuit is
        // checked independently of isEnabled() - both conditions are
        // OR'd in checkBatch's guard clause.
        ReflectionTestUtils.setField(client, "enabled", true);
        ReflectionTestUtils.setField(client, "apiKey", "test-key");

        assertTrue(client.checkBatch(List.of()).isEmpty());
    }

    @Test
    void checkFallback_returnsUnavailable() throws Exception {
        SafeBrowsingResult result = invokeCheckFallback("https://example.com", new RuntimeException("boom"));

        assertEquals(SafeBrowsingResult.Status.UNAVAILABLE, result.status());
        assertFalse(result.isConclusive());
    }

    @Test
    void checkBatchFallback_returnsEmptyMap() throws Exception {
        Map<String, List<String>> result = invokeCheckBatchFallback(
                List.of("https://example.com", "https://example.org"), new RuntimeException("boom"));

        assertTrue(result.isEmpty());
    }

    @SuppressWarnings("unchecked")
    private SafeBrowsingResult invokeCheckFallback(String url, Throwable ex) throws Exception {
        Method method = SafeBrowsingClient.class.getDeclaredMethod("checkFallback", String.class, Throwable.class);
        method.setAccessible(true);
        return (SafeBrowsingResult) method.invoke(client, url, ex);
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<String>> invokeCheckBatchFallback(List<String> urls, Throwable ex) throws Exception {
        Method method = SafeBrowsingClient.class.getDeclaredMethod("checkBatchFallback", List.class, Throwable.class);
        method.setAccessible(true);
        return (Map<String, List<String>>) method.invoke(client, urls, ex);
    }
}
