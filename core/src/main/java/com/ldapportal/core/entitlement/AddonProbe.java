// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.entitlement;

import java.util.Set;

/**
 * SPI implemented by open-source addons in {@code addons/*} that
 * grant {@link Entitlement entitlements} by virtue of their bytecode
 * being present on the classpath (rather than by license file or
 * settings flag).
 *
 * <p>Each addon module ships a Spring-registered {@code AddonProbe}
 * bean returning the entitlements that addon enables. The
 * {@code addons/isva} module, for example, registers a probe that
 * returns {@code {VENDOR_INTEGRATIONS_ISVA}}. The community
 * distribution depends only on {@code core/} and ships no probes;
 * any distribution that adds an addon module brings the matching
 * probe along by Spring auto-configuration.</p>
 *
 * <p>{@link LicenseAutoConfiguration} wires the probes via
 * {@code List<AddonProbe>} injection. After choosing the base
 * provider (file / settings / community), it wraps the result in
 * an {@link AddonProbingLicenseProvider} that unions every probe's
 * {@link #probedEntitlements()} into the final license's
 * {@link License#addOns()}.</p>
 *
 * <p>Why a probe-and-union shape rather than putting the addon's
 * entitlement in an {@link Edition} baseline: addons are
 * orthogonal to tier. An open-source addon may be loaded on a
 * community-tier deployment (which has no baseline entitlements)
 * just as freely as on an Enterprise-tier one. Edition tiers
 * govern paid feature bundles; classpath probes govern "is this
 * code available."</p>
 *
 * <p>An empty probe (returning {@link Set#of()}) is a no-op and is
 * the right answer for an addon whose code is loaded but whose
 * features are not enabled for some other reason (e.g. an admin
 * disabled the addon via a future toggle).</p>
 */
public interface AddonProbe {

    /**
     * Entitlements this addon grants when its bytecode is present.
     * Called once at startup and on every license refresh; should
     * be cheap and side-effect-free.
     *
     * <p>Returns a non-null set. An addon that's loaded but
     * currently inactive may return {@link Set#of()}.</p>
     */
    Set<Entitlement> probedEntitlements();

    /**
     * Short human-readable identifier for this addon, used in
     * startup logging so operators can confirm which addons are
     * granting entitlements. Default impl uses the implementation
     * class's simple name, which is usually fine.
     */
    default String addonName() {
        return getClass().getSimpleName();
    }
}
