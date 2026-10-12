package com.example.urlshortener.exception;

/** The email provider rejected or couldn't be reached. The message is safe to show users. */
public class EmailDeliveryException extends RuntimeException {
    public EmailDeliveryException(String detail, Throwable cause) {
        super("We couldn't send the email right now. Please try again in a moment.", cause);
        this.detail = detail;
    }

    private final String detail;

    /** Technical detail for the server log only. */
    public String getDetail() { return detail; }
}
