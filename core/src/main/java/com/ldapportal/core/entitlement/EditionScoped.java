// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.entitlement;

/**
 * Contract for a "catalogue" enum whose constants are edition-scoped — i.e. a
 * value may only exist in editions/licenses that hold a particular
 * {@link Entitlement}. Implemented by the enums the UI enumerates and ships to
 * the client ({@code FeatureKey}, {@code AuditAction}, …) so a single,
 * authoritative source of truth answers "is this value part of the current
 * edition's surface?".
 *
 * <p><b>Why this exists.</b> Several UI surfaces enumerate a catalogue enum and
 * expose the full value set. When some constants belong to a non-community
 * feature (access reviews, segregation of duties, HR sync, the auditor portal),
 * forgetting to filter leaks that constant into the community app. Routing every
 * such enumeration through {@link EntitlementService#exposed(Class)} /
 * {@link EntitlementService#exposes(EditionScoped)} — which consults this
 * method — keeps the gate in one place. {@code EditionLeakGuardTest} (behavioural)
 * and an ArchUnit rule (structural, bans raw {@code values()} on these enums from
 * controllers) keep new surfaces from regressing.</p>
 *
 * <p>This governs <em>visibility</em> only; execution stays gated independently
 * by {@link Entitled}.</p>
 */
public interface EditionScoped {

    /**
     * The entitlement that must be present for this constant to be exposed, or
     * {@code null} when it is part of the core, edition-agnostic surface (always
     * exposed).
     */
    Entitlement requiredEntitlement();
}
