// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link CryptoService}.
 */
class CryptoServiceTest {

    private CryptoService cryptoService;

    @BeforeEach
    void setUp() {
        byte[] keyBytes = new byte[32];
        new SecureRandom().nextBytes(keyBytes);
        String keyBase64 = Base64.getEncoder().encodeToString(keyBytes);

        AppProperties props = new AppProperties();
        AppProperties.Encryption enc = new AppProperties.Encryption();
        enc.setKey(keyBase64);
        props.setEncryption(enc);

        cryptoService = new CryptoService(props);
    }

    @Test
    void generateToken_returns43CharBase64UrlString() {
        String token = cryptoService.generateToken();
        // 32 bytes -> Base64URL without padding = 43 chars
        assertThat(token).hasSize(43);
        // Must be valid Base64URL (no +, /, or = characters)
        assertThat(token).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void generateToken_producesUniqueTokens() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            tokens.add(cryptoService.generateToken());
        }
        assertThat(tokens).hasSize(100);
    }

    @Test
    void sha256Hex_emptyInput_producesKnownHash() {
        String hash = cryptoService.sha256Hex(new byte[0]);
        assertThat(hash).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void sha256Hex_returns64CharHexString() {
        String hash = cryptoService.sha256Hex("hello world".getBytes());
        assertThat(hash).hasSize(64);
        assertThat(hash).matches("[0-9a-f]+");
    }

    @Test
    void hmacSha256_isDeterministic() {
        byte[] data = "test data".getBytes();
        String sig1 = cryptoService.hmacSha256(data);
        String sig2 = cryptoService.hmacSha256(data);
        assertThat(sig1).isEqualTo(sig2);
    }

    @Test
    void hmacSha256_returns64CharHexString() {
        String sig = cryptoService.hmacSha256("test".getBytes());
        assertThat(sig).hasSize(64);
        assertThat(sig).matches("[0-9a-f]+");
    }

    @Test
    void hmacSha256_differentInputs_produceDifferentSignatures() {
        String sig1 = cryptoService.hmacSha256("input1".getBytes());
        String sig2 = cryptoService.hmacSha256("input2".getBytes());
        assertThat(sig1).isNotEqualTo(sig2);
    }

    @Test
    void hmacSha256_differentKeys_produceDifferentSignatures() {
        String sig1 = cryptoService.hmacSha256("same data".getBytes());

        // Create a second service with a different key
        byte[] keyBytes2 = new byte[32];
        new SecureRandom().nextBytes(keyBytes2);
        AppProperties props2 = new AppProperties();
        AppProperties.Encryption enc2 = new AppProperties.Encryption();
        enc2.setKey(Base64.getEncoder().encodeToString(keyBytes2));
        props2.setEncryption(enc2);
        CryptoService service2 = new CryptoService(props2);

        String sig2 = service2.hmacSha256("same data".getBytes());
        assertThat(sig1).isNotEqualTo(sig2);
    }
}
