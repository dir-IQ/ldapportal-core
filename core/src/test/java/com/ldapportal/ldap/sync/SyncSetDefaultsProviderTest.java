// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.dto.sync.SyncSetDefaults;
import com.ldapportal.ldap.sync.identity.EntraIdentityStrategy;
import com.ldapportal.ldap.sync.identity.EntryUuidIdentityStrategy;
import com.ldapportal.ldap.sync.identity.IdentityStrategyRegistry;
import com.ldapportal.ldap.sync.identity.ObjectGuidIdentityStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The editor's defaults come from the engine's own sources. */
class SyncSetDefaultsProviderTest {

    @Test
    void defaults_areTheEnginesFallbacks() {
        IdentityStrategyRegistry registry = new IdentityStrategyRegistry(List.of(
                new EntryUuidIdentityStrategy(), new ObjectGuidIdentityStrategy(), new EntraIdentityStrategy()));

        SyncSetDefaults d = new SyncSetDefaultsProvider(registry, 1800).defaults();

        assertThat(d.identityKeyByDirectoryType())
                .containsEntry("OPENLDAP", "entryUUID")
                .containsEntry("ORACLE_UNIFIED_DIRECTORY", "entryUUID")
                .containsEntry("ACTIVE_DIRECTORY", "objectGUID")
                .containsEntry("GENERIC", "entryUUID");
        assertThat(d.referenceAttributes()).isEqualTo(SyncReferenceAttributes.DEFAULTS);
        assertThat(d.reconcileIntervalSeconds()).isEqualTo(1800);
        assertThat(d.excludedAttributes()).isEqualTo(SyncExcludedAttributes.DEFAULT_EXCLUDED);
    }
}
