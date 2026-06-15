// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.entitlement;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Emits a summary of the current license + entitlements on application
 * start. Covers operator diagnostics ("is this install actually running
 * with governance on?") and flags license-health concerns (approaching
 * expiry, within grace period, past grace).
 *
 * <p>Pairs with the per-module gating: the log lines here record the
 * <em>license</em> state; the actual bean-level gating (ee controllers
 * wrapped with {@link Entitled}) enforces it at request time.</p>
 *
 * <p>Depends on {@link LicenseProvider} only to get a {@link #source()}
 * one-liner — everything else comes from {@link EntitlementService}.
 * When the provider is {@link FileLicenseProvider} it also owns a
 * {@link LicenseVerifier} which we consult to classify expiry state.</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class EntitlementStartupReporter {

    private final EntitlementService entitlementService;
    private final LicenseProvider licenseProvider;

    @EventListener(ApplicationReadyEvent.class)
    void reportOnStartup() {
        License lic = entitlementService.current();
        Instant now = Instant.now();

        Set<Entitlement> all = EnumSet.allOf(Entitlement.class);
        Set<Entitlement> granted = all.stream()
                .filter(lic::has)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Entitlement.class)));
        Set<Entitlement> withheld = all.stream()
                .filter(e -> !lic.has(e))
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Entitlement.class)));

        log.info("Entitlements: edition={} granted={} withheld={} source=\"{}\"",
                lic.edition(), granted, withheld, licenseProvider.source());

        // Expiry / grace reporting only meaningful for real (file-backed)
        // licenses. Settings-derived licenses use Instant.MAX as a sentinel
        // and don't produce a useful "days remaining".
        if (lic.expiresAt().equals(Instant.MAX)) {
            return;
        }

        LicenseVerifier verifier = (licenseProvider instanceof FileLicenseProvider flp)
                ? flp.verifier()
                : null;
        long daysToExpiry = Duration.between(now, lic.expiresAt()).toDays();

        if (verifier != null && verifier.isPastGrace(lic, now)) {
            log.error("License past grace period — expired {} ({} days ago). "
                    + "Renew the license; future releases will refuse to start.",
                    lic.expiresAt(), Math.abs(daysToExpiry));
        } else if (verifier != null && verifier.isExpired(lic, now)) {
            log.warn("License EXPIRED — {} (within {} day grace period). Renew soon.",
                    lic.expiresAt(), verifier.graceDuration().toDays());
        } else if (daysToExpiry < 30) {
            log.warn("License expires in {} days on {} — plan to renew",
                    daysToExpiry, lic.expiresAt());
        } else {
            log.info("License expires in {} days on {}", daysToExpiry, lic.expiresAt());
        }
    }
}
