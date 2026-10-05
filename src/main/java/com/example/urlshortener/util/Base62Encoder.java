package com.example.urlshortener.util;

/**
 * Encodes a positive long (typically a database auto-increment id) into a
 * short, URL-safe Base62 string, and can decode it back.
 *
 * Using the DB id as the seed (instead of a random string) guarantees no
 * collisions without needing a "generate and retry" loop, and keeps codes
 * short: a 62^6 keyspace (~56 billion) only needs 6 characters.
 */
public final class Base62Encoder {

    private static final String ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int BASE = ALPHABET.length();

    private Base62Encoder() {
    }

    public static String encode(long value) {
        if (value == 0) {
            return String.valueOf(ALPHABET.charAt(0));
        }
        StringBuilder sb = new StringBuilder();
        long v = value;
        while (v > 0) {
            int remainder = (int) (v % BASE);
            sb.append(ALPHABET.charAt(remainder));
            v /= BASE;
        }
        return sb.reverse().toString();
    }

    public static long decode(String code) {
        long result = 0;
        for (char c : code.toCharArray()) {
            int digit = ALPHABET.indexOf(c);
            if (digit < 0) {
                throw new IllegalArgumentException("Invalid Base62 character: " + c);
            }
            result = result * BASE + digit;
        }
        return result;
    }
}
