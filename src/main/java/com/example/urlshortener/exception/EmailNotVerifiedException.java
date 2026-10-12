package com.example.urlshortener.exception;

/** Correct password, but the account's email address hasn't been verified yet. */
public class EmailNotVerifiedException extends RuntimeException {
    private final String email;

    public EmailNotVerifiedException(String email) {
        super("Please verify your email address before logging in.");
        this.email = email;
    }

    public String getEmail() { return email; }
}
