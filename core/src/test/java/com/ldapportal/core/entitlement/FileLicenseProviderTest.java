// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.entitlement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileLicenseProviderTest {

    @Test
    void loads_and_verifies_valid_license_file(@TempDir Path tempDir) throws Exception {
        KeyPair kp = LicenseSigner.freshKeypair();
        LicenseVerifier verifier = LicenseSigner.verifierFor(kp.getPublic());
        Instant exp = Instant.now().plus(Duration.ofDays(365));
        String jwt = LicenseSigner.signLicense(
                kp.getPrivate(), LicenseSigner.Claims.enterprise(exp));

        Path licensePath = tempDir.resolve("license.jwt");
        Files.writeString(licensePath, jwt);

        FileLicenseProvider provider = new FileLicenseProvider(licensePath, verifier);

        assertThat(provider.current().edition()).isEqualTo(Edition.ENTERPRISE);
        assertThat(provider.source()).isEqualTo(licensePath.toString());
    }

    @Test
    void rejects_license_signed_with_wrong_key(@TempDir Path tempDir) throws Exception {
        KeyPair issuer = LicenseSigner.freshKeypair();
        KeyPair verifierKey = LicenseSigner.freshKeypair();
        LicenseVerifier verifier = LicenseSigner.verifierFor(verifierKey.getPublic());

        String jwt = LicenseSigner.signLicense(
                issuer.getPrivate(),
                LicenseSigner.Claims.enterprise(Instant.now().plus(Duration.ofDays(30))));
        Path licensePath = tempDir.resolve("license.jwt");
        Files.writeString(licensePath, jwt);

        // Provider construction must eagerly verify and fail so Spring startup halts
        // rather than silently running without a valid license.
        assertThatThrownBy(() -> new FileLicenseProvider(licensePath, verifier))
                .isInstanceOf(LicenseInvalidException.class);
    }

    @Test
    void rejects_empty_license_file(@TempDir Path tempDir) throws Exception {
        LicenseVerifier verifier = LicenseSigner.verifierFor(LicenseSigner.freshKeypair().getPublic());
        Path licensePath = tempDir.resolve("license.jwt");
        Files.writeString(licensePath, "");

        assertThatThrownBy(() -> new FileLicenseProvider(licensePath, verifier))
                .isInstanceOf(LicenseInvalidException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void rejects_missing_license_file(@TempDir Path tempDir) {
        LicenseVerifier verifier = LicenseSigner.verifierFor(LicenseSigner.freshKeypair().getPublic());
        Path missing = tempDir.resolve("no-such-file.jwt");

        assertThatThrownBy(() -> new FileLicenseProvider(missing, verifier))
                .isInstanceOf(LicenseInvalidException.class)
                .hasMessageContaining("Failed to read");
    }

    @Test
    void accepts_expired_license_within_grace_but_flags_it(@TempDir Path tempDir) throws Exception {
        KeyPair kp = LicenseSigner.freshKeypair();
        LicenseVerifier verifier = LicenseSigner.verifierFor(kp.getPublic());

        // Expired 5 days ago, within the default 30-day grace. Provider
        // construction succeeds — a hard fail here would take down a
        // commercial install for a one-day administrative lapse, which
        // is exactly what the grace period exists to prevent.
        String jwt = LicenseSigner.signLicense(
                kp.getPrivate(),
                LicenseSigner.Claims.enterprise(Instant.now().minus(Duration.ofDays(5))));
        Path licensePath = tempDir.resolve("license.jwt");
        Files.writeString(licensePath, jwt);

        FileLicenseProvider provider = new FileLicenseProvider(licensePath, verifier);

        Instant now = Instant.now();
        assertThat(verifier.isExpired(provider.current(), now)).isTrue();
        assertThat(verifier.isPastGrace(provider.current(), now)).isFalse();
    }
}
