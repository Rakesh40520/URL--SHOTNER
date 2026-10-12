package com.example.urlshortener.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClickSourceTest {

    @Test
    void keepsOnlyTheRefererHost_notThePathOrQuery() {
        ClickSource s = ClickSource.from("https://www.Twitter.com/someone/status/1?token=secret", null, "short.example");
        assertEquals("twitter.com", s.referrerHost());
        assertNull(s.referralTag());
    }

    @Test
    void ignoresRefererFromOurOwnSite() {
        assertNull(ClickSource.from("https://short.example/history.html", null, "short.example").referrerHost());
    }

    @Test
    void missingOrMalformedReferer_meansNoReferrer() {
        assertNull(ClickSource.from(null, null, "x").referrerHost());
        assertNull(ClickSource.from("   ", null, "x").referrerHost());
        assertNull(ClickSource.from("not a url at all %%", null, "x").referrerHost());
    }

    @Test
    void refTag_acceptsSafeValues_andRejectsAnythingElse() {
        assertEquals("alice", ClickSource.from(null, "alice", "x").referralTag());
        assertEquals("qr", ClickSource.from(null, "qr", "x").referralTag());
        assertNull(ClickSource.from(null, "<script>alert(1)</script>", "x").referralTag());
        assertNull(ClickSource.from(null, "a".repeat(31), "x").referralTag());
        assertNull(ClickSource.from(null, "", "x").referralTag());
    }
}
