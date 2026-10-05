package com.example.urlshortener.entity;

/**
 * Lifecycle state of a {@link UrlMapping}, separate from expiry.
 * Expiry is time-based and self-service (isExpired()); status is the
 * abuse-protection axis layered on top of that:
 *
 * <ul>
 *   <li>{@code ACTIVE}   - resolves normally, default for every new link.</li>
 *   <li>{@code FLAGGED}  - the scheduled Safe Browsing re-check (or enough
 *       public reports) found a threat match after creation. Blocked from
 *       resolving, but distinct from DISABLED so it's visible in "My
 *       Dispatches" as something the owner didn't do themselves.</li>
 *   <li>{@code DISABLED} - the owner (or management-key holder) turned the
 *       link off deliberately. Also blocked from resolving.</li>
 * </ul>
 *
 * Both FLAGGED and DISABLED behave identically at redirect time (see
 * UrlShortenerService.resolve) - the distinction is only for the owner's
 * own visibility into *why* a link stopped working.
 */
public enum UrlStatus {
    ACTIVE,
    FLAGGED,
    DISABLED
}
