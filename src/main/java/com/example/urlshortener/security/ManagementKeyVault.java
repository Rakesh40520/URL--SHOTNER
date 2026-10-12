package com.example.urlshortener.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Stores a signed-in owner's management key so it can be shown again later,
 * instead of being lost after the one-time reveal.
 *
 * <p>The key is encrypted at rest (AES-256-GCM, random IV per value) with a key
 * derived from app.key-vault.secret (defaults to JWT_SECRET). A database leak
 * alone therefore does not reveal keys. The bcrypt hash used to VERIFY keys is
 * unchanged. Only links owned by an account get a stored key; anonymous links
 * keep the original behaviour (the browser holds the key).
 *
 * <p>If the secret is ever changed, previously stored keys can no longer be
 * decrypted: decrypt() then returns null and the UI simply doesn't offer the key
 * (ownership still grants access to the link's stats).
 */
@Component
public class ManagementKeyVault {

    private static final Logger log = LoggerFactory.getLogger(ManagementKeyVault.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int IV_BYTES = 12, TAG_BITS = 128;

    private final SecretKeySpec key;

    public ManagementKeyVault(@Value("${app.key-vault.secret:${jwt.secret}}") String secret) {
        try {
            byte[] k = MessageDigest.getInstance("SHA-256")
                    .digest(("mgmt-key-vault:" + secret).getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(k, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Cannot initialise management key vault", e);
        }
    }

    /** @return base64(iv || ciphertext+tag), or null if encryption fails (key just isn't stored). */
    public String encrypt(String plain) {
        if (plain == null) return null;
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            log.warn("Could not encrypt management key: {}", e.toString());
            return null;
        }
    }

    /** @return the original key, or null if there's nothing stored or it can't be decrypted. */
    public String decrypt(String stored) {
        if (stored == null || stored.isBlank()) return null;
        try {
            byte[] in = Base64.getDecoder().decode(stored);
            if (in.length <= IV_BYTES) return null;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
            return new String(c.doFinal(in, IV_BYTES, in.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;   // wrong secret / corrupted value
        }
    }
}
