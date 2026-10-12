package com.example.urlshortener.safebrowsing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenPhishClientTest {

    private final OpenPhishClient client = new OpenPhishClient(new ObjectMapper());

    @Test
    void isEnabled_isTrueByDefault() {
        assertTrue(client.isEnabled());
    }

    @Test
    void check_whenDisabled_returnsSafeWithoutNetworkCall() {
        client.setEnabled(false);
        OpenPhishResult result = client.check("https://suspicious-phish.com");

        assertNotNull(result);
        assertFalse(result.found());
    }

    @Test
    void check_withNullOrBlankUrl_returnsSafe() {
        OpenPhishResult nullResult = client.check(null);
        OpenPhishResult blankResult = client.check("   ");

        assertFalse(nullResult.found());
        assertFalse(blankResult.found());
    }
}
