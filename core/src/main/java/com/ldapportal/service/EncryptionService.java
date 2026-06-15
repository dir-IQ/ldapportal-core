// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.config.AppProperties;
import com.ldapportal.exception.EncryptionException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256 GCM authenticated encryption for sensitive values stored in the
 * database (e.g. LDAP bind passwords).
 *
 * <p>Wire format: {@code Base64( IV[12] || Ciphertext || GCM-Tag[16] )}.
 * A fresh random IV is generated per encryption call so that identical
 * plaintexts produce different ciphertexts, preventing oracle attacks.</p>
 */
@Service
@RequiredArgsConstructor
public class EncryptionService {

    private static final String ALGORITHM       = "AES/GCM/NoPadding";
    private static final int    IV_LENGTH_BYTES  = 12;
    private static final int    TAG_LENGTH_BITS  = 128;

    private final AppProperties appProperties;

    /**
     * Encrypts {@code plaintext} and returns a Base64-encoded string that
     * contains the IV and ciphertext (including the GCM authentication tag).
     */
    public String encrypt(String plaintext) {
        try {
            SecretKeySpec key = loadKey();

            byte[] iv = new byte[IV_LENGTH_BYTES];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (EncryptionException e) {
            throw e;
        } catch (Exception e) {
            throw new EncryptionException("Encryption failed", e);
        }
    }

    /**
     * Decrypts a value that was previously produced by {@link #encrypt(String)}.
     *
     * @throws EncryptionException if the ciphertext is malformed or the
     *                             authentication tag does not match.
     */
    public String decrypt(String encryptedBase64) {
        try {
            SecretKeySpec key = loadKey();

            byte[] combined = Base64.getDecoder().decode(encryptedBase64);
            if (combined.length <= IV_LENGTH_BYTES) {
                throw new EncryptionException("Ciphertext too short");
            }

            byte[] iv         = new byte[IV_LENGTH_BYTES];
            byte[] ciphertext = new byte[combined.length - IV_LENGTH_BYTES];
            System.arraycopy(combined, 0, iv, 0, iv.length);
            System.arraycopy(combined, iv.length, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));

            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (EncryptionException e) {
            throw e;
        } catch (Exception e) {
            throw new EncryptionException("Decryption failed", e);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private SecretKeySpec loadKey() {
        byte[] keyBytes = Base64.getDecoder().decode(appProperties.getEncryption().getKey());
        if (keyBytes.length != 32) {
            throw new EncryptionException(
                "Encryption key must be 32 bytes (256 bits) when base64-decoded, got " + keyBytes.length);
        }
        return new SecretKeySpec(keyBytes, "AES");
    }
}
