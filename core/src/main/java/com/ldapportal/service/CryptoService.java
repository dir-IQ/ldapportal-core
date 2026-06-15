// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Shared cryptographic operations: token generation, HMAC-SHA256 signing,
 * and SHA-256 checksums.
 *
 * <p>Extracted from the ee governance {@code EvidencePackageService} so
 * that both the evidence package builder and the auditor link service
 * can share the same crypto primitives without circular dependencies.</p>
 */
@Service
@RequiredArgsConstructor
public class CryptoService {

    private final AppProperties appProperties;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** Token length in bytes (256 bits). Base64URL encoding yields ~43 chars. */
    private static final int TOKEN_BYTES = 32;

    /**
     * Generates a 256-bit cryptographically random token, Base64URL-encoded
     * (no padding). Suitable for use as an unguessable credential.
     */
    public String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Computes HMAC-SHA256 over {@code data} using the application's
     * encryption key. Returns the signature as a lowercase hex string.
     */
    public String hmacSha256(byte[] data) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(appProperties.getEncryption().getKey());
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
            byte[] hmac = mac.doFinal(data);
            return bytesToHex(hmac);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 signing failed", e);
        }
    }

    /**
     * Computes SHA-256 hash of {@code data} and returns it as a lowercase
     * hex string (64 characters).
     */
    public String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return bytesToHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
