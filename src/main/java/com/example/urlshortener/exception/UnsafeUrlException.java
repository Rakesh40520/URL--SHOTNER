package com.example.urlshortener.exception;

/** Thrown when Safe Browsing (or fail-closed unavailability) blocks a URL from being shortened at all. */
public class UnsafeUrlException extends RuntimeException {
    public UnsafeUrlException(String message) {
        super(message);
    }
}
