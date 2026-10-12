package com.example.urlshortener.exception;

/** Too many code emails requested too quickly for one address. */
public class CodeRateLimitException extends RuntimeException {
    private final long retryAfterSeconds;

    public CodeRateLimitException(long retryAfterSeconds) {
        super("Please wait " + retryAfterSeconds + " seconds before requesting another code.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() { return retryAfterSeconds; }
}
