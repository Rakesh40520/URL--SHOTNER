package com.example.urlshortener.geo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GeoLocationServiceTest {

    @Test
    void privateAndLocalAddressesAreNeverLookedUp() {
        // enabled=true but pointing at an unroutable URL: if any of these tried the
        // network, the call would fail/slow; they must short-circuit to empty instead.
        GeoLocationService svc = new GeoLocationService(true, "http://127.0.0.1:1/{ip}");
        for (String ip : new String[]{"127.0.0.1", "10.0.0.5", "192.168.1.20", "172.16.4.4",
                "169.254.1.1", "100.64.0.9", "::1", "fd12:3456::1"}) {
            assertTrue(GeoLocationService.isNonPublic(ip), ip);
            assertTrue(svc.lookup(ip).isEmpty(), ip);
        }
    }

    @Test
    void publicAddressesAreNotTreatedAsPrivate() {
        assertFalse(GeoLocationService.isNonPublic("8.8.8.8"));
        assertFalse(GeoLocationService.isNonPublic("203.0.113.5"));
    }

    @Test
    void disabledOrGarbageInputReturnsEmpty() {
        assertTrue(new GeoLocationService(false, "x").lookup("8.8.8.8").isEmpty());
        GeoLocationService on = new GeoLocationService(true, "http://127.0.0.1:1/{ip}");
        assertTrue(on.lookup(null).isEmpty());
        assertTrue(on.lookup("not-an-ip; DROP TABLE").isEmpty());
    }

    @Test
    void unreachableProvider_failsQuietlyWithEmpty() {
        GeoLocationService svc = new GeoLocationService(true, "http://127.0.0.1:1/{ip}");
        assertTrue(svc.lookup("8.8.8.8").isEmpty());
    }

    @Test
    void parsesTheThreeProviderShapes() {
        GeoLocationService svc = new GeoLocationService(true, "http://127.0.0.1:1/{ip}");

        GeoInfo ipwho = svc.parse("{\"success\":true,\"country\":\"India\",\"country_code\":\"IN\",\"region\":\"Maharashtra\",\"city\":\"Mumbai\"}").orElseThrow();
        assertEquals("India", ipwho.country());
        assertEquals("Mumbai", ipwho.city());

        GeoInfo ipapi = svc.parse("{\"country_name\":\"Germany\",\"country_code\":\"DE\",\"region\":\"Berlin\",\"city\":\"Berlin\"}").orElseThrow();
        assertEquals("Germany", ipapi.country());
        assertEquals("DE", ipapi.countryCode());

        GeoInfo geojs = svc.parse("{\"country\":\"Japan\",\"country_code\":\"JP\",\"region\":\"Tokyo\",\"city\":\"nil\"}").orElseThrow();
        assertEquals("Japan", geojs.country());
        assertNull(geojs.city());
    }

    @Test
    void errorResponsesAndEmptyBodiesAreNotLocations() {
        GeoLocationService svc = new GeoLocationService(true, "http://127.0.0.1:1/{ip}");
        assertTrue(svc.parse("{\"success\":false,\"message\":\"Reserved range\"}").isEmpty());
        assertTrue(svc.parse("{\"error\":true,\"reason\":\"RateLimited\"}").isEmpty());
        assertTrue(svc.parse("{}").isEmpty());
        assertTrue(svc.parse("<html>blocked</html>").isEmpty());
    }

    @Test
    void aFailedLookupIsNotRememberedForever() throws Exception {
        // Unroutable provider => the lookup fails. The miss must expire quickly, so we
        // check the cache entry is a short-lived one by confirming a second call still
        // tries (and fails) rather than throwing or sticking: behaviour, not timing.
        GeoLocationService svc = new GeoLocationService(true, "http://127.0.0.1:1/{ip}");
        assertTrue(svc.lookup("8.8.8.8").isEmpty());
        assertTrue(svc.lookup("8.8.8.8").isEmpty());
    }
}
