// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.admin;

import com.ldapportal.entity.enums.FeatureKey;

/**
 * One entry in the feature-permission catalogue: the full set of features an
 * admin override can target. The {@code key} is the stable enum name used by
 * the override endpoints (e.g. {@code USER_CREATE}); {@code dbValue} is the
 * dot-notation identifier surfaced in the UI (e.g. {@code user.create}).
 *
 * <p>Serving this from the backend keeps the editor's grid in lock-step with
 * {@link FeatureKey} so it can't drift like a hand-maintained list.</p>
 */
public record FeatureCatalogEntry(FeatureKey key, String dbValue) {

    public static FeatureCatalogEntry of(FeatureKey key) {
        return new FeatureCatalogEntry(key, key.getDbValue());
    }
}
