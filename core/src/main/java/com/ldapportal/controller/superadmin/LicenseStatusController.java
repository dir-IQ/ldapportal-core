// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.superadmin;

import com.ldapportal.core.entitlement.Entitlement;
import com.ldapportal.core.entitlement.EntitlementService;
import com.ldapportal.core.entitlement.FileLicenseProvider;
import com.ldapportal.core.entitlement.License;
import com.ldapportal.core.entitlement.LicenseProvider;
import com.ldapportal.core.entitlement.LicenseVerifier;
import com.ldapportal.dto.license.LicenseStatusDto;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only view of the currently-active license. Superadmin-only —
 * the customer id and raw entitlement list aren't the kind of thing
 * every admin needs to see, and the operator who cares about license
 * health is the same superadmin who installed the JAR.
 *
 * <pre>
 *   GET /api/v1/license/status  — current edition, add-ons, expiry, grace state
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/license")
@RequiredArgsConstructor
public class LicenseStatusController {

    /** Threshold at which {@code graceState} flips to APPROACHING_EXPIRY. */
    private static final long APPROACHING_EXPIRY_DAYS = 30;

    private final EntitlementService entitlementService;
    private final LicenseProvider licenseProvider;

    @GetMapping("/status")
    @PreAuthorize("hasRole('SUPERADMIN')")
    public LicenseStatusDto status() {
        License lic = entitlementService.current();
        Instant now = Instant.now();

        List<String> granted = new ArrayList<>();
        List<String> withheld = new ArrayList<>();
        for (Entitlement e : Entitlement.values()) {
            if (lic.has(e)) granted.add(e.name());
            else withheld.add(e.name());
        }

        List<String> addOns = lic.addOns().stream().map(Enum::name).sorted().toList();
        Map<String, Long> limits = new HashMap<>();
        lic.limits().forEach((k, v) -> limits.put(k.name(), v));

        // Distinguish settings-derived (sentinel Instant.MAX, null signature)
        // from a real signed license. The wire shape hides the raw JWT but
        // surfaces enough for the UI to explain the install's state.
        boolean signed = lic.signature() != null;
        boolean hasRealExpiry = !lic.expiresAt().equals(Instant.MAX);

        OffsetDateTime issuedAt = (lic.issuedAt().equals(Instant.EPOCH))
                ? null
                : OffsetDateTime.ofInstant(lic.issuedAt(), ZoneOffset.UTC);
        OffsetDateTime expiresAt = hasRealExpiry
                ? OffsetDateTime.ofInstant(lic.expiresAt(), ZoneOffset.UTC)
                : null;
        Long daysRemaining = hasRealExpiry
                ? Duration.between(now, lic.expiresAt()).toDays()
                : null;

        // Grace state is computed from the license + grace duration so it
        // works regardless of which LicenseProvider backs us. Grace
        // duration itself comes from a FileLicenseProvider's verifier when
        // available (to mirror whatever startup enforcement used); otherwise
        // we fall back to the default.
        String graceState;
        Long graceDays;
        if (!hasRealExpiry) {
            graceState = "NO_EXPIRY";
            graceDays = null;
        } else {
            java.time.Duration grace = (licenseProvider instanceof FileLicenseProvider flp)
                    ? flp.verifier().graceDuration()
                    : LicenseVerifier.DEFAULT_GRACE;
            graceDays = grace.toDays();

            Instant graceCutoff = lic.expiresAt().plus(grace);
            if (now.isAfter(graceCutoff)) {
                graceState = "PAST_GRACE";
            } else if (now.isAfter(lic.expiresAt())) {
                graceState = "EXPIRED_WITHIN_GRACE";
            } else if (daysRemaining != null && daysRemaining < APPROACHING_EXPIRY_DAYS) {
                graceState = "APPROACHING_EXPIRY";
            } else {
                graceState = "VALID";
            }
        }

        return new LicenseStatusDto(
                lic.edition().name(),
                lic.customerId() != null ? lic.customerId().toString() : null,
                signed,
                addOns,
                granted,
                withheld,
                limits,
                issuedAt,
                expiresAt,
                daysRemaining,
                graceState,
                graceDays,
                licenseProvider.source());
    }
}
