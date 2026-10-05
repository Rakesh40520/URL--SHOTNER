package com.example.urlshortener.util;

import java.security.SecureRandom;

/**
 * Generates random, URL-safe Base62 codes of a fixed length.
 *
 * Used for auto-generated short codes instead of encoding the database id
 * (see Base62Encoder) so that:
 *  - Creating a link is a single INSERT with the final shortCode already
 *    known, rather than "insert a placeholder, then update it once the id
 *    comes back" - which used to leave a brief window where a row could be
 *    left with an invalid "tmp-..." code if the process died between the
 *    two writes.
 *  - Codes don't reveal how many links have been created or let someone
 *    enumerate other users' links by walking sequential ids.
 */
public final class RandomCodeGenerator {

    private static final String ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final SecureRandom RANDOM = new SecureRandom();

    private RandomCodeGenerator() {
    }

    public static String generate(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
