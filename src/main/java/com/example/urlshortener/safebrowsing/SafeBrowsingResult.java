package com.example.urlshortener.safebrowsing;

import java.util.List;

/**
 * Outcome of a Google Safe Browsing lookup for a single URL.
 *
 * SAFE and UNSAFE are answers from the API. DISABLED and UNAVAILABLE both
 * mean "we don't actually know" - kept as separate states (rather than
 * collapsing to one "unknown") so logs/metrics can tell "the feature is
 * off" apart from "the API call itself failed" - but both are handled the
 * same way by callers via {@link #isConclusive()}: whether creation/redirect
 * proceeds when the answer isn't known falls back to
 * {@code app.safe-browsing.fail-open}.
 */
public record SafeBrowsingResult(Status status, List<String> threatTypes) {

    public enum Status { SAFE, UNSAFE, UNAVAILABLE, DISABLED }

    public static SafeBrowsingResult safe() {
        return new SafeBrowsingResult(Status.SAFE, List.of());
    }

    public static SafeBrowsingResult unsafe(List<String> threatTypes) {
        return new SafeBrowsingResult(Status.UNSAFE, threatTypes);
    }

    public static SafeBrowsingResult unavailable() {
        return new SafeBrowsingResult(Status.UNAVAILABLE, List.of());
    }

    public static SafeBrowsingResult disabled() {
        return new SafeBrowsingResult(Status.DISABLED, List.of());
    }

    public boolean isUnsafe() {
        return status == Status.UNSAFE;
    }

    /** True if the API was actually consulted and gave a real SAFE/UNSAFE answer. */
    public boolean isConclusive() {
        return status == Status.SAFE || status == Status.UNSAFE;
    }
}
