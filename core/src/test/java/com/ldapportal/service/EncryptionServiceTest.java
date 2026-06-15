// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.config.AppProperties;
import com.ldapportal.exception.EncryptionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link EncryptionService}.
 * No Spring context — the service is instantiated directly with a fabricated
 * {@link AppProperties} instance.
 */
class EncryptionServiceTest {

    private EncryptionService encryptionService;

    @BeforeEach
    void setUp() {
        // Generate a random 32-byte key for each test run
        byte[] keyBytes = new byte[32];
        new SecureRandom().nextBytes(keyBytes);
        String keyBase64 = Base64.getEncoder().encodeToString(keyBytes);

        AppProperties props = new AppProperties();
        AppProperties.Encryption enc = new AppProperties.Encryption();
        enc.setKey(keyBase64);
        props.setEncryption(enc);

        encryptionService = new EncryptionService(props);
    }

    @Test
    void encrypt_thenDecrypt_returnsOriginalPlaintext() {
        String plaintext = "s3cret-bind-password!";
        String encrypted = encryptionService.encrypt(plaintext);
        assertThat(encryptionService.decrypt(encrypted)).isEqualTo(plaintext);
    }

    @Test
    void encrypt_producesDifferentCiphertextEachCall() {
        String plaintext = "same-password";
        String c1 = encryptionService.encrypt(plaintext);
        String c2 = encryptionService.encrypt(plaintext);
        assertThat(c1).isNotEqualTo(c2);
    }

    @Test
    void encrypt_ciphertextIsBase64Encoded() {
        String encrypted = encryptionService.encrypt("hello");
        // Should not throw
        byte[] decoded = Base64.getDecoder().decode(encrypted);
        // IV (12) + at least 1 byte plaintext + 16-byte GCM tag = > 28 bytes
        assertThat(decoded.length).isGreaterThan(28);
    }

    @Test
    void decrypt_withTamperedCiphertext_throwsEncryptionException() {
        String encrypted = encryptionService.encrypt("legit");
        // Flip the last byte to invalidate the GCM authentication tag
        byte[] raw = Base64.getDecoder().decode(encrypted);
        raw[raw.length - 1] ^= 0xFF;
        String tampered = Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> encryptionService.decrypt(tampered))
            .isInstanceOf(EncryptionException.class);
    }

    @Test
    void decrypt_withTooShortInput_throwsEncryptionException() {
        // A valid Base64 string that decodes to fewer bytes than the IV length
        String tooShort = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});
        assertThatThrownBy(() -> encryptionService.decrypt(tooShort))
            .isInstanceOf(EncryptionException.class)
            .hasMessageContaining("too short");
    }

    @Test
    void encrypt_withWrongKeyLength_throwsEncryptionException() {
        // 16-byte key — valid AES-128 but not the required 32 bytes
        byte[] shortKey = new byte[16];
        new SecureRandom().nextBytes(shortKey);

        AppProperties props = new AppProperties();
        AppProperties.Encryption enc = new AppProperties.Encryption();
        enc.setKey(Base64.getEncoder().encodeToString(shortKey));
        props.setEncryption(enc);

        EncryptionService service = new EncryptionService(props);
        assertThatThrownBy(() -> service.encrypt("anything"))
            .isInstanceOf(EncryptionException.class)
            .hasMessageContaining("32 bytes");
    }

    @Test
    void encrypt_emptyString_roundTripsSuccessfully() {
        String encrypted = encryptionService.encrypt("");
        assertThat(encryptionService.decrypt(encrypted)).isEqualTo("");
    }

    @Test
    void encrypt_unicodeString_roundTripsSuccessfully() {
        String unicode = "pässwörð — привет — 日本語";
        assertThat(encryptionService.decrypt(encryptionService.encrypt(unicode)))
            .isEqualTo(unicode);
    }
}
