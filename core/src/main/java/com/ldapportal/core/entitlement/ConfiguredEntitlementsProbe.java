// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.entitlement;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Operator self-host override: grants entitlements named in
 * {@code ldapportal.entitlements.grant} (CSV; env
 * {@code LDAPPORTAL_ENTITLEMENTS_GRANT}) <em>without</em> a signed license
 * file. For self-hosters who build from source and want to switch on an
 * open-source feature — notably directory replication
 * ({@link Entitlement#DIRECTORY_SYNC}) — without standing up the
 * license-signing toolchain.
 *
 * <p>Rides the existing {@link AddonProbe} SPI, so the grant flows through
 * the single entitlement chokepoint ({@code EntitlementService.has(...)} via
 * {@link AddonProbingLicenseProvider}) exactly like a classpath addon — no
 * feature-site changes, no parallel gate. The bean is inert unless the
 * property is set ({@link ConditionalOnProperty}).
 *
 * <p><b>Deliberately narrow.</b> Only entitlements in {@link #SELF_GRANTABLE}
 * — features whose implementing code is Apache-2.0 — may be granted this
 * way. Requests for commercial/EE entitlements are refused and logged, so
 * this knob cannot unlock the paid edition bundles; it can only flip on
 * already-open-source features. <b>Do not add EE entitlements here</b> — that
 * would turn it into a licensing bypass.
 */
@Component
@ConditionalOnProperty(name = "ldapportal.entitlements.grant")
@Slf4j
public class ConfiguredEntitlementsProbe implements AddonProbe {

    /**
     * The only entitlements an operator may self-grant via config. Backed
     * by Apache-2.0 code, so granting them locally doesn't circumvent
     * commercial licensing of the EE modules.
     */
    static final Set<Entitlement> SELF_GRANTABLE = EnumSet.of(Entitlement.DIRECTORY_SYNC);

    private final Set<Entitlement> granted;

    public ConfiguredEntitlementsProbe(
            @Value("${ldapportal.entitlements.grant:}") String csv) {
        this.granted = parse(csv);
    }

    private static Set<Entitlement> parse(String csv) {
        Set<Entitlement> requested = new LinkedHashSet<>();
        Set<String> unknown = new LinkedHashSet<>();
        for (String raw : csv.split(",")) {
            String name = raw.trim();
            if (name.isEmpty()) continue;
            try {
                requested.add(Entitlement.valueOf(name));
            } catch (IllegalArgumentException ex) {
                unknown.add(name);
            }
        }
        if (!unknown.isEmpty()) {
            log.warn("ldapportal.entitlements.grant lists unknown entitlement(s) {} — ignored", unknown);
        }

        Set<Entitlement> ok = EnumSet.noneOf(Entitlement.class);
        Set<Entitlement> refused = EnumSet.noneOf(Entitlement.class);
        for (Entitlement e : requested) {
            (SELF_GRANTABLE.contains(e) ? ok : refused).add(e);
        }
        if (!refused.isEmpty()) {
            log.warn("ldapportal.entitlements.grant requested non-self-grantable entitlement(s) {} — "
                   + "refused. Only {} may be granted via config; the rest require a signed license.",
                    refused, SELF_GRANTABLE);
        }
        if (!ok.isEmpty()) {
            log.warn("Granting entitlement(s) {} via ldapportal.entitlements.grant — operator self-host "
                   + "override, NOT a signed license. Do not use to bypass commercial licensing.", ok);
        }
        return ok.isEmpty() ? Set.of() : Set.copyOf(ok);
    }

    @Override
    public Set<Entitlement> probedEntitlements() {
        return granted;
    }

    @Override
    public String addonName() {
        return "config-grant";
    }
}
