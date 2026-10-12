package com.example.urlshortener.safebrowsing;

/**
 * Result returned from querying the OpenPhish API.
 */
public record OpenPhishResult(boolean found, String targetBrand, String status, Integer id) {

    public static OpenPhishResult safe() {
        return new OpenPhishResult(false, null, null, null);
    }

    public static OpenPhishResult phish(String targetBrand, String status, Integer id) {
        return new OpenPhishResult(true, targetBrand, status, id);
    }
}
