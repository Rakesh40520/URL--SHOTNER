package com.example.urlshortener.exception;

public class InvalidManagementKeyException extends RuntimeException {
    public InvalidManagementKeyException() {
        super("A valid management key (or ownership of this link) is required to view its tracking data.");
    }
}
