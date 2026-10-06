package com.example.urlshortener.geo;

/** Where a click came from, as resolved from its IP address. Any field may be null. */
public record GeoInfo(String country, String countryCode, String region, String city) {
}
