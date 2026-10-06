package com.example.urlshortener.dto;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * Who/what sent a click: the website it came from (host only - never the full
 * Referer URL, which can carry private paths or tokens) and an optional ?ref=
 * tag the link's owner put on the link, e.g. /abc1234?ref=alice.
 */
public record ClickSource(String referrerHost, String referralTag) {

    public static final ClickSource NONE = new ClickSource(null, null);
    private static final Pattern TAG = Pattern.compile("^[A-Za-z0-9_-]{1,30}$");

    public static ClickSource from(String refererHeader, String refParam, String ownHost) {
        String host = null;
        if (refererHeader != null && !refererHeader.isBlank()) {
            try {
                String h = URI.create(refererHeader.trim()).getHost();
                if (h != null) {
                    h = h.toLowerCase();
                    if (h.startsWith("www.")) h = h.substring(4);
                    boolean internal = ownHost != null && h.equalsIgnoreCase(ownHost.replaceFirst("^www\\.", ""));
                    if (!internal) host = h.length() > 120 ? h.substring(0, 120) : h;
                }
            } catch (Exception ignored) { /* malformed header -> treat as no referrer */ }
        }
        String tag = refParam != null && TAG.matcher(refParam).matches() ? refParam : null;
        return new ClickSource(host, tag);
    }
}
