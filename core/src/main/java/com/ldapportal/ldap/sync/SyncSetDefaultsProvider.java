// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.dto.sync.SyncSetDefaults;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.ldap.sync.identity.IdentityStrategyRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Collects the values the engine falls back to for blank sync-set fields, from
 * the same sources the engine reads them from.
 */
@Component
public class SyncSetDefaultsProvider {

    private final IdentityStrategyRegistry identityStrategies;
    private final long defaultReconcileIntervalSeconds;

    public SyncSetDefaultsProvider(IdentityStrategyRegistry identityStrategies,
                                   @Value("${ldapportal.sync.reconcile.default-cadence-seconds:3600}")
                                   long defaultReconcileIntervalSeconds) {
        this.identityStrategies = identityStrategies;
        this.defaultReconcileIntervalSeconds = defaultReconcileIntervalSeconds;
    }

    public SyncSetDefaults defaults() {
        Map<String, String> identityKeys = new LinkedHashMap<>();
        for (DirectoryType type : DirectoryType.values()) {
            try {
                identityKeys.put(type.name(), identityStrategies.forType(type).identityAttribute());
            } catch (IllegalArgumentException unsupported) {
                // No strategy for this type: it can't be a sync source, so there's no default to show.
            }
        }
        return new SyncSetDefaults(identityKeys, SyncReferenceAttributes.DEFAULTS,
                defaultReconcileIntervalSeconds, SyncExcludedAttributes.DEFAULT_EXCLUDED);
    }
}
