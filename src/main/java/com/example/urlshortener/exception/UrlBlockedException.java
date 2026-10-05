package com.example.urlshortener.exception;

/**
 * Thrown when {@code GET /{shortCode}} resolves to a link whose status is
 * FLAGGED or DISABLED (see UrlStatus). Deliberately does not echo the
 * specific reason to the caller - the owner sees that in "My Dispatches"
 * instead - since an anonymous visitor hitting a blocked link doesn't need
 * (and shouldn't get) detail about why it was flagged.
 */
public class UrlBlockedException extends RuntimeException {
    public UrlBlockedException(String shortCode) {
        super("This link has been disabled: " + shortCode);
    }
}
