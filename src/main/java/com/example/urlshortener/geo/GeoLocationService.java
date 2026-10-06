package com.example.urlshortener.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Turns an IP address into a country/region/city using free HTTPS IP-geolocation
 * APIs. Several providers are tried in order, so one being down, rate-limited or
 * blocking the server's IP doesn't mean no location. Only ever called from the
 * background worker, never on the redirect path.
 *
 * <p>Caching: successful lookups are kept for 24h. Failures are only remembered
 * for 5 minutes (a transient outage must not mark an IP as "unknown" forever).
 * Private/loopback addresses are never sent to any provider.
 *
 * <p>Config: app.geo.enabled, and app.geo.url-templates - a comma-separated list
 * of URLs with {ip} as the placeholder (no commas inside a URL). Each provider
 * may name the fields country/country_name, country_code, region/region_name, city.
 */
@Service
public class GeoLocationService {

    private static final Logger log = LoggerFactory.getLogger(GeoLocationService.class);
    private static final Pattern IP_LITERAL = Pattern.compile("^[0-9a-fA-F:.]{3,45}$");
    private static final int MAX_CACHE = 2000;
    private static final long HIT_TTL_MS = Duration.ofHours(24).toMillis();
    private static final long MISS_TTL_MS = Duration.ofMinutes(5).toMillis();
    private static final long WARN_EVERY_MS = Duration.ofMinutes(1).toMillis();

    private record Entry(Optional<GeoInfo> info, long expiresAt) { }

    private final boolean enabled;
    private final List<String> urlTemplates;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong lastWarn = new AtomicLong(0);

    private final Map<String, Entry> cache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Entry> e) { return size() > MAX_CACHE; }
    };

    public GeoLocationService(
            @Value("${app.geo.enabled:true}") boolean enabled,
            @Value("${app.geo.url-templates:https://ipwho.is/{ip},https://get.geojs.io/v1/ip/geo/{ip}.json,https://ipapi.co/{ip}/json/}") String urlTemplates) {
        this.enabled = enabled;
        List<String> list = new ArrayList<>();
        for (String t : urlTemplates.split(",")) if (!t.isBlank()) list.add(t.trim());
        this.urlTemplates = list;
    }

    public Optional<GeoInfo> lookup(String ip) {
        if (!enabled || ip == null || !IP_LITERAL.matcher(ip).matches() || isNonPublic(ip)) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        synchronized (cache) {
            Entry e = cache.get(ip);
            if (e != null && e.expiresAt() > now) return e.info();
        }
        Optional<GeoInfo> result = fetchFromProviders(ip);
        synchronized (cache) {
            cache.put(ip, new Entry(result, now + (result.isPresent() ? HIT_TTL_MS : MISS_TTL_MS)));
        }
        return result;
    }

    private Optional<GeoInfo> fetchFromProviders(String ip) {
        String lastProblem = "no providers configured";
        for (String template : urlTemplates) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(template.replace("{ip}", ip)))
                        .timeout(Duration.ofSeconds(4))
                        .header("User-Agent", "url-shortener/1.0")
                        .header("Accept", "application/json")
                        .GET().build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() != 200) { lastProblem = template + " -> HTTP " + res.statusCode(); continue; }

                Optional<GeoInfo> parsed = parse(res.body());
                if (parsed.isPresent()) return parsed;
                lastProblem = template + " -> no location in response";
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } catch (Exception ex) {
                lastProblem = template + " -> " + ex.getClass().getSimpleName();
            }
        }
        warnOncePerMinute("Geo lookup failed for all providers (" + lastProblem + "). Click locations will stay unknown until one works.");
        return Optional.empty();
    }

    /** Parses the common field names used by ipwho.is / geojs / ipapi.co. Package-private for tests. */
    Optional<GeoInfo> parse(String body) {
        try {
            JsonNode n = mapper.readTree(body);
            if (n.path("success").isBoolean() && !n.get("success").asBoolean()) return Optional.empty();
            if (n.path("error").asBoolean(false)) return Optional.empty();

            String country = firstText(n, "country", "country_name");
            String code    = firstText(n, "country_code", "country_code2");
            String region  = firstText(n, "region", "region_name");
            String city    = firstText(n, "city");
            if (country == null && city == null) return Optional.empty();
            return Optional.of(new GeoInfo(country, code, region, city));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String firstText(JsonNode n, String... fields) {
        for (String f : fields) {
            JsonNode v = n.get(f);
            if (v != null && !v.isNull() && v.isValueNode() && !v.asText().isBlank()
                    && !"null".equalsIgnoreCase(v.asText()) && !"nil".equalsIgnoreCase(v.asText())) {
                String s = v.asText().trim();
                return s.length() > 100 ? s.substring(0, 100) : s;
            }
        }
        return null;
    }

    private void warnOncePerMinute(String msg) {
        long now = System.currentTimeMillis(), last = lastWarn.get();
        if (now - last > WARN_EVERY_MS && lastWarn.compareAndSet(last, now)) log.warn(msg);
    }

    /** Loopback, private (RFC1918), link-local, IPv6 unique-local and carrier-grade NAT ranges. */
    public static boolean isNonPublic(String ip) {
        try {
            InetAddress a = InetAddress.getByName(ip); // literal only (validated above) - no DNS lookup
            if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                    || a.isSiteLocalAddress() || a.isMulticastAddress()) return true;
            byte[] b = a.getAddress();
            if (b.length == 16) return (b[0] & 0xFE) == 0xFC;                   // fc00::/7
            return (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64;                // 100.64.0.0/10
        } catch (Exception e) {
            return true;
        }
    }
}
