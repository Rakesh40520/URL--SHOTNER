package com.example.urlshortener.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ManagementKeyVaultTest {

    private final ManagementKeyVault vault = new ManagementKeyVault("a-test-secret-that-is-long-enough-1234567890");

    @Test
    void roundTrips_andNeverStoresPlaintext() {
        String enc = vault.encrypt("Abcd2345Efgh6789Jklm2345");
        assertNotNull(enc);
        assertFalse(enc.contains("Abcd2345"));
        assertEquals("Abcd2345Efgh6789Jklm2345", vault.decrypt(enc));
    }

    @Test
    void sameKeyEncryptsDifferentlyEachTime() {
        assertNotEquals(vault.encrypt("same-key"), vault.encrypt("same-key"));
    }

    @Test
    void wrongSecretOrGarbage_decryptsToNullInsteadOfThrowing() {
        String enc = vault.encrypt("secret-key");
        assertNull(new ManagementKeyVault("a-different-secret-entirely-0987654321").decrypt(enc));
        assertNull(vault.decrypt("not-base64-!!!"));
        assertNull(vault.decrypt("AAAA"));
        assertNull(vault.decrypt(null));
        assertNull(vault.decrypt(" "));
    }

    @Test
    void tamperedCiphertext_isRejected() {
        String enc = vault.encrypt("secret-key");
        byte[] raw = java.util.Base64.getDecoder().decode(enc);
        raw[raw.length - 1] ^= 0x01;
        assertNull(vault.decrypt(java.util.Base64.getEncoder().encodeToString(raw)));
    }
}
