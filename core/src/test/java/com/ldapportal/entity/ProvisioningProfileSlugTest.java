// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the {@link ProvisioningProfile#ensureSlug()} safety net — the
 * {@code @PrePersist} that guarantees the NOT NULL slug invariant for persist
 * paths (clone, tests) that don't set a clean slug up front.
 */
class ProvisioningProfileSlugTest {

    @Test
    void ensureSlug_derivesFromName_whenAbsent() {
        ProvisioningProfile p = new ProvisioningProfile();
        p.setName("Standard User");

        p.ensureSlug();

        // Derived from the name, with a short random suffix for uniqueness.
        assertThat(p.getSlug()).startsWith("standard-user-");
        assertThat(p.getSlug()).matches("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    }

    @Test
    void ensureSlug_keepsExplicitSlug() {
        ProvisioningProfile p = new ProvisioningProfile();
        p.setName("Standard User");
        p.setSlug("pinned-slug");

        p.ensureSlug();

        assertThat(p.getSlug()).isEqualTo("pinned-slug");
    }

    @Test
    void ensureSlug_blankName_fallsBackToProfile() {
        ProvisioningProfile p = new ProvisioningProfile();
        p.setName("   ");

        p.ensureSlug();

        assertThat(p.getSlug()).startsWith("profile-");
    }
}
