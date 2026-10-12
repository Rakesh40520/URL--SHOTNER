package com.example.urlshortener.safebrowsing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Client for OpenPhish API (https://openphish.eu/api).
 * Queries the community-maintained phishing URL database at GET /api/check?url={url}.
 */
@Component
public class OpenPhishClient {

    private static final Logger log = LoggerFactory.getLogger(OpenPhishClient.class);
    private static final String DEFAULT_BASE_URL = "https://openphish.eu/api";

    private final HttpClient http;
    private final ObjectMapper mapper;

    @Value("${app.openphish.enabled:true}")
    private boolean enabled = true;

    @Value("${app.openphish.base-url:https://openphish.eu/api}")
    private String baseUrl;

    @Value("${app.openphish.api-key:}")
    private String apiKey;

    public OpenPhishClient(ObjectMapper mapper) {
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Checks if a URL is found in the OpenPhish database.
     * Fails open (returns safe) if the OpenPhish API is unreachable, times out, or errors.
     */
    public OpenPhishResult check(String url) {
        if (!enabled || url == null || url.isBlank()) {
            return OpenPhishResult.safe();
        }

        try {
            String encoded = URLEncoder.encode(url.trim(), StandardCharsets.UTF_8);
            String checkUrl = (baseUrl != null && !baseUrl.isBlank() ? baseUrl : DEFAULT_BASE_URL)
                    + "/check?url=" + encoded;

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(URI.create(checkUrl))
                    .timeout(Duration.ofSeconds(4))
                    .header("Accept", "application/json")
                    .GET();

            if (apiKey != null && !apiKey.isBlank()) {
                reqBuilder.header("X-API-Key", apiKey);
                reqBuilder.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> res = http.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() == 200 && res.body() != null) {
                JsonNode root = mapper.readTree(res.body());
                boolean found = root.path("found").asBoolean(false);
                if (found) {
                    JsonNode phish = root.path("phish");
                    String brand = phish.path("target_brand").asText(null);
                    String status = phish.path("status").asText("verified");
                    Integer id = phish.has("id") ? phish.path("id").asInt() : null;
                    log.warn("OpenPhish detected phishing URL: {} (target brand: {}, status: {})", url, brand, status);
                    return OpenPhishResult.phish(brand, status, id);
                }
            } else if (res.statusCode() == 429) {
                log.warn("OpenPhish rate limit reached (HTTP 429). Allowing request to fail open.");
            } else {
                log.debug("OpenPhish returned HTTP {}: {}", res.statusCode(), res.body());
            }
        } catch (Exception ex) {
            log.warn("OpenPhish check failed for URL (failing open): {}", ex.getMessage());
        }

        return OpenPhishResult.safe();
    }

    /**
     * Submits a newly reported phishing URL to OpenPhish database at POST /api/phish.
     */
    public void submit(String url, String targetBrand) {
        if (!enabled || url == null || url.isBlank()) {
            return;
        }

        try {
            String submitUrl = (baseUrl != null && !baseUrl.isBlank() ? baseUrl : DEFAULT_BASE_URL) + "/phish";
            Map<String, String> payload = new HashMap<>();
            payload.put("url", url.trim());
            if (targetBrand != null && !targetBrand.isBlank()) {
                payload.put("targetBrand", targetBrand.trim());
            }

            String json = mapper.writeValueAsString(payload);
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(URI.create(submitUrl))
                    .timeout(Duration.ofSeconds(4))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json));

            if (apiKey != null && !apiKey.isBlank()) {
                reqBuilder.header("X-API-Key", apiKey);
                reqBuilder.header("Authorization", "Bearer " + apiKey);
            }

            http.send(reqBuilder.build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception ex) {
            log.debug("OpenPhish submission failed: {}", ex.getMessage());
        }
    }
}
