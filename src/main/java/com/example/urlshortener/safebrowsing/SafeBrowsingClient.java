package com.example.urlshortener.safebrowsing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around the Google Safe Browsing v4 {@code threatMatches:find}
 * endpoint (docs: https://developers.google.com/safe-browsing/v4/lookup-api).
 * Used two ways by {@link com.example.urlshortener.service.UrlShortenerService}:
 * a single-URL {@link #check} synchronously at link creation, and a batched
 * {@link #checkBatch} from the scheduled job that re-checks already-active
 * links (a destination can turn malicious well after the short link was
 * created).
 *
 * <p>Entirely inert unless {@code app.safe-browsing.enabled=true} AND an API
 * key is configured - see {@link #isEnabled()}. Every caller is expected to
 * treat {@link SafeBrowsingResult.Status#DISABLED} and
 * {@code UNAVAILABLE} (API call failed/timed out, or the circuit is open -
 * see below) the same way: fall back to {@code app.safe-browsing.fail-open}
 * rather than treating "we couldn't check" as either a hard pass or a hard
 * block.
 *
 * <p>{@link #check} and {@link #checkBatch} are both wrapped in a
 * resilience4j circuit breaker (instance name {@code safeBrowsing}, config
 * in application.yml). A degraded/unreachable Safe Browsing API would
 * otherwise mean every single link creation eats a full connect+read
 * timeout while it's down; once the failure rate trips the breaker, calls
 * fail immediately into the fallback methods below instead, without even
 * attempting the network call, until the wait duration elapses and a
 * half-open trial resumes.
 */
@Component
public class SafeBrowsingClient {

    private static final Logger log = LoggerFactory.getLogger(SafeBrowsingClient.class);

    private static final String ENDPOINT = "https://safebrowsing.googleapis.com/v4/threatMatches:find";

    // The threat types relevant to "would clicking this link hurt someone" -
    // not Safe Browsing's full list, which also covers platform-specific app
    // threats this project has no use for.
    private static final List<String> THREAT_TYPES = List.of(
            "MALWARE", "SOCIAL_ENGINEERING", "UNWANTED_SOFTWARE", "POTENTIALLY_HARMFUL_APPLICATION"
    );

    // Safe Browsing's documented cap on threatEntries per threatMatches:find
    // request. The scheduled re-check job chunks its URL list to this size -
    // see UrlShortenerService.recheckActiveLinksForThreats.
    public static final int MAX_URLS_PER_REQUEST = 500;

    @Value("${app.safe-browsing.enabled:false}")
    private boolean enabled;

    @Value("${app.safe-browsing.api-key:}")
    private String apiKey;

    @Value("${app.safe-browsing.client-id:dispatch-url-shortener}")
    private String clientId;

    @Value("${app.safe-browsing.connect-timeout-ms:2000}")
    private int connectTimeoutMs;

    @Value("${app.safe-browsing.read-timeout-ms:3000}")
    private int readTimeoutMs;

    private final ObjectMapper objectMapper;
    private volatile RestClient restClient;

    public SafeBrowsingClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public boolean isEnabled() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    /** Single-URL check, called synchronously while a link is being created. */
    @CircuitBreaker(name = "safeBrowsing", fallbackMethod = "checkFallback")
    public SafeBrowsingResult check(String url) {
        if (!isEnabled()) {
            return SafeBrowsingResult.disabled();
        }
        Map<String, List<String>> matches = lookup(List.of(url));
        List<String> threats = matches.get(url);
        return threats == null ? SafeBrowsingResult.safe() : SafeBrowsingResult.unsafe(threats);
    }

    // Resilience4j fallback: invoked in place of check() whenever it throws
    // (a real call failure) OR the circuit is currently open (failing fast,
    // no call attempted at all) - same signature as check() plus a
    // Throwable, same return type. This is now the only place that
    // "unavailable" gets produced for the single-URL path - deliberately
    // not caught inside check() itself, since a caught-and-swallowed
    // exception would never reach the circuit breaker to be counted as a
    // failure in the first place.
    private SafeBrowsingResult checkFallback(String url, Throwable ex) {
        log.warn("Safe Browsing check failed/circuit open - treating as unavailable ({}): {}", url, ex.getMessage());
        return SafeBrowsingResult.unavailable();
    }

    /**
     * Batch check for the scheduled re-check job. {@code urls} must not
     * exceed {@link #MAX_URLS_PER_REQUEST} - callers chunk larger lists
     * themselves.
     *
     * @return URL -> threat types, containing only URLs that matched. A URL
     *         absent from the result is either genuinely safe, or - if the
     *         whole call failed - simply not checked this round; a batch
     *         response has no per-URL "unavailable" signal, so a failed call
     *         returns an empty map rather than guessing.
     */
    @CircuitBreaker(name = "safeBrowsing", fallbackMethod = "checkBatchFallback")
    public Map<String, List<String>> checkBatch(List<String> urls) {
        if (!isEnabled() || urls.isEmpty()) {
            return Map.of();
        }
        return lookup(urls);
    }

    private Map<String, List<String>> checkBatchFallback(List<String> urls, Throwable ex) {
        log.warn("Safe Browsing batch check failed/circuit open for {} URLs - skipping this round: {}",
                urls.size(), ex.getMessage());
        return Map.of();
    }

    private Map<String, List<String>> lookup(List<String> urls) {
        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode client = body.putObject("client");
        client.put("clientId", clientId);
        client.put("clientVersion", "1.0");

        ObjectNode threatInfo = body.putObject("threatInfo");
        ArrayNode threatTypesNode = threatInfo.putArray("threatTypes");
        THREAT_TYPES.forEach(threatTypesNode::add);
        threatInfo.putArray("platformTypes").add("ANY_PLATFORM");
        threatInfo.putArray("threatEntryTypes").add("URL");
        ArrayNode threatEntries = threatInfo.putArray("threatEntries");
        urls.forEach(u -> threatEntries.addObject().put("url", u));

        JsonNode response = restClient().post()
                .uri(uriBuilder -> uriBuilder.queryParam("key", apiKey).build())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        Map<String, List<String>> result = new HashMap<>();
        if (response == null || !response.has("matches")) {
            // An empty (or absent) "matches" array is Safe Browsing's way of
            // saying "nothing in this batch is known-bad" - not an error.
            return result;
        }
        for (JsonNode match : response.get("matches")) {
            String url = match.path("threat").path("url").asText(null);
            if (url == null) {
                continue;
            }
            String threatType = match.path("threatType").asText("UNKNOWN");
            result.computeIfAbsent(url, k -> new ArrayList<>()).add(threatType);
        }
        return result;
    }

    private RestClient restClient() {
        RestClient client = restClient;
        if (client == null) {
            synchronized (this) {
                if (restClient == null) {
                    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout(connectTimeoutMs);
                    factory.setReadTimeout(readTimeoutMs);
                    restClient = RestClient.builder()
                            .baseUrl(ENDPOINT)
                            .requestFactory(factory)
                            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                            .build();
                }
                client = restClient;
            }
        }
        return client;
    }
}
