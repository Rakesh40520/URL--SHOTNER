package com.example.urlshortener.exception;

/** The emailed code was wrong, expired, or used up. attemptsLeft is -1 when not applicable. */
public class InvalidCodeException extends RuntimeException {
    private final int attemptsLeft;

    public InvalidCodeException(String message, int attemptsLeft) {
        super(message);
        this.attemptsLeft = attemptsLeft;
    }

    public static InvalidCodeException expiredOrUnknown() {
        return new InvalidCodeException("That code is invalid or has expired. Request a new one.", -1);
    }

    public int getAttemptsLeft() { return attemptsLeft; }
}
