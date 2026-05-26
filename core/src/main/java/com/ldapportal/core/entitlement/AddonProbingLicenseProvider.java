// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.entitlement;

import lombok.extern.slf4j.Slf4j;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * {@link LicenseProvider} decorator that unions
 * {@link AddonProbe#probedEntitlements()} from every registered
 * {@link AddonProbe} into the base provider's
 * {@link License#addOns()}. Lets open-source addons in
 * {@code addons/*} grant entitlements by classpath presence
 * regardless of which base provider (community / settings / file)
 * is in play.
 *
 * <p>Pass-through behaviour for every other license field
 * ({@code customerId}, {@code edition}, {@code limits},
 * {@code issuedAt}, {@code expiresAt}, {@code signature}) and for
 * {@link #source()}. The decorator never invents a higher edition
 * tier; it only adds add-ons. A community-tier license with an
 * ISVA probe present reports {@code edition=COMMUNITY,
 * addOns=[VENDOR_INTEGRATIONS_ISVA]}, not {@code edition=BUSINESS}.</p>
 *
 * <p>Logged once at startup so operators can confirm which addons
 * contributed entitlements; subsequent {@link #current()} calls
 * are silent.</p>
 */
@Slf4j
public class AddonProbingLicenseProvider implements LicenseProvider {

    private final LicenseProvider base;
    private final List<AddonProbe> probes;
    private boolean loggedAtLeastOnce = false;

    public AddonProbingLicenseProvider(LicenseProvider base, List<AddonProbe> probes) {
        this.base = base;
        // Defensive copy + null-tolerant: Spring injects an empty list when
        // no AddonProbe beans are registered, which is the community
        // distribution's normal state.
        this.probes = probes == null ? List.of() : List.copyOf(probes);
    }

    @Override
    public License current() {
        License baseLicense = base.current();
        if (probes.isEmpty()) {
            return baseLicense;
        }

        Set<Entitlement> unioned = EnumSet.noneOf(Entitlement.class);
        unioned.addAll(baseLicense.addOns());
        for (AddonProbe probe : probes) {
            Set<Entitlement> probed = probe.probedEntitlements();
            if (probed != null && !probed.isEmpty()) {
                unioned.addAll(probed);
                if (!loggedAtLeastOnce) {
                    log.info("Addon probe {} granted entitlements: {}",
                            probe.addonName(), probed);
                }
            }
        }
        loggedAtLeastOnce = true;

        return new License(
                baseLicense.customerId(),
                baseLicense.edition(),
                unioned,
                baseLicense.limits(),
                baseLicense.issuedAt(),
                baseLicense.expiresAt(),
                baseLicense.signature());
    }

    @Override
    public String source() {
        return base.source();
    }
}
