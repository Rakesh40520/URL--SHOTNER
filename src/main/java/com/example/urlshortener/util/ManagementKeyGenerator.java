package com.example.urlshortener.util;

import java.security.SecureRandom;

/**
 * Generates the one-time management key shown to a link's creator right
 * after dispatch. Uses SecureRandom (not Math.random()) since this is a
 * security-relevant secret, and an alphabet with ambiguous characters
 * (0/O, 1/l/I) removed so a key is easy to read back and retype correctly.
 */
public final class ManagementKeyGenerator {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final int LENGTH = 24;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ManagementKeyGenerator() {
    }

    public static String generate() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
