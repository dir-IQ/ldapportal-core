// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.service;

import com.ldapportal.addons.isva.entity.IsvaProfileOverride;
import com.ldapportal.addons.isva.entity.IsvaProfileOverrideEntity;
import com.ldapportal.addons.isva.repository.IsvaProfileOverrideRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Resolves and persists the per-profile ISVA override.
 *
 * <p>Narrowing-only semantics (see the design spec): the directory
 * {@code enabled} flag is the authoritative on/kill-switch — enforced
 * upstream by the interceptor's per-directory config lookup — and a
 * profile can only narrow it. {@link #isExemptFromIvia(UUID)} answers
 * the per-profile half of that rule.</p>
 */
@Service
@RequiredArgsConstructor
public class IsvaProfileOverrideService {

    private final IsvaProfileOverrideRepository repository;

    /**
     * Whether the given profile is exempt from ISVA provisioning
     * ({@code FORCE_OFF}). A profile with no row, or {@code INHERIT},
     * is not exempt — it follows the directory. A {@code null}
     * profile id (the unprovisioned-OU / legacy path) is never exempt;
     * it falls through to directory-level behaviour.
     *
     * <p>This is only the profile-narrowing half: the directory kill
     * switch ({@code enabled = false}) is enforced separately by the
     * interceptor, which already returns the baseline plan for a
     * directory with no active ISVA config.</p>
     */
    @Transactional(readOnly = true)
    public boolean isExemptFromIvia(UUID profileId) {
        return getOverride(profileId) == IsvaProfileOverride.FORCE_OFF;
    }

    /**
     * The stored override for a profile, or {@link IsvaProfileOverride#INHERIT}
     * when there's no row (or {@code profileId} is null).
     */
    @Transactional(readOnly = true)
    public IsvaProfileOverride getOverride(UUID profileId) {
        if (profileId == null) {
            return IsvaProfileOverride.INHERIT;
        }
        return repository.findById(profileId)
                .map(IsvaProfileOverrideEntity::getOverride)
                .orElse(IsvaProfileOverride.INHERIT);
    }

    /**
     * Upsert the override for a profile. Setting {@code INHERIT} keeps
     * the row (carrying the audit columns) rather than deleting it —
     * the distinction between "never configured" and "explicitly set
     * back to inherit" doesn't matter to resolution, and keeping the
     * row preserves {@code updated_by} / {@code updated_at}.
     */
    @Transactional
    public IsvaProfileOverride setOverride(UUID profileId,
                                           IsvaProfileOverride override,
                                           String updatedBy) {
        IsvaProfileOverrideEntity entity = repository.findById(profileId)
                .orElseGet(IsvaProfileOverrideEntity::new);
        entity.setProfileId(profileId);
        entity.setOverride(override == null ? IsvaProfileOverride.INHERIT : override);
        entity.setUpdatedBy(updatedBy);
        return repository.save(entity).getOverride();
    }
}
