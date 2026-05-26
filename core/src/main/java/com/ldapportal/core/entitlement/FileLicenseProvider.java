// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.entitlement;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * {@link LicenseProvider} that loads a signed JWT from a configurable
 * path and verifies it with {@link LicenseVerifier}. Registered by
 * {@code LicenseAutoConfiguration} when
 * {@code ldapportal.license.path} is set and the file exists.
 *
 * <p>Initialization eagerly verifies the license. If the file is
 * malformed, the signature is wrong, or a required claim is missing,
 * the bean construction throws {@link LicenseInvalidException} and
 * Spring startup fails — that's the intended behaviour for a
 * commercial install pointed at a bad file. The path to fall back to
 * the settings-derived license is to <em>not</em> configure a
 * license path at all; misconfigured commercial installs don't
 * silently degrade.</p>
 *
 * <p>Expiration is checked at every {@link #current()} call so
 * grace-period logic reflects current time, not startup time. A
 * license that expires while the JVM is running will flip to
 * expired-with-grace and eventually past-grace without requiring a
 * restart.</p>
 */
@Slf4j
public class FileLicenseProvider implements LicenseProvider {

    private final Path licensePath;
    private final LicenseVerifier verifier;
    private final License license;

    public FileLicenseProvider(Path licensePath, LicenseVerifier verifier) {
        this.licensePath = licensePath;
        this.verifier = verifier;
        this.license = loadAndVerify();
        logStartupStatus();
    }

    @Override
    public License current() {
        return license;
    }

    @Override
    public String source() {
        return licensePath.toString();
    }

    public LicenseVerifier verifier() {
        return verifier;
    }

    private License loadAndVerify() {
        String jwt;
        try {
            jwt = Files.readString(licensePath).trim();
        } catch (Exception e) {
            throw new LicenseInvalidException(
                    "Failed to read license file " + licensePath + ": " + e.getMessage(), e);
        }
        if (jwt.isEmpty()) {
            throw new LicenseInvalidException("License file is empty: " + licensePath);
        }
        return verifier.verify(jwt);
    }

    private void logStartupStatus() {
        Instant now = Instant.now();
        Duration remaining = Duration.between(now, license.expiresAt());
        if (verifier.isPastGrace(license, now)) {
            log.error("License past grace period — edition={} customer={} expired={} "
                    + "grace={} days. Commercial entitlements will continue to apply in this "
                    + "pass but a future release will refuse to start. Renew the license.",
                    license.edition(), license.customerId(),
                    license.expiresAt(), verifier.graceDuration().toDays());
        } else if (verifier.isExpired(license, now)) {
            log.warn("License EXPIRED — edition={} customer={} expired={}. Within "
                    + "{} day grace period; running normally but renew soon.",
                    license.edition(), license.customerId(),
                    license.expiresAt(), verifier.graceDuration().toDays());
        } else {
            log.info("License loaded — edition={} customer={} expires={} ({} days remaining) "
                    + "addOns={} source={}",
                    license.edition(), license.customerId(),
                    license.expiresAt(), Math.max(0, remaining.toDays()),
                    license.addOns(), licensePath);
        }
    }
}
