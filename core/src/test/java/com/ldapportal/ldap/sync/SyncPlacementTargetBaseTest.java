// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.SyncSet;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SyncPlacementTargetBaseTest {

    private static SyncSet set(String sourceBase, String targetBase) {
        SyncSet s = new SyncSet();
        s.setObjectScopeBaseDn(sourceBase);
        s.setTargetBaseDn(targetBase);
        return s;
    }

    @Test
    void effectiveTargetBase_isTheTargetBase_elseTheSourceScope_elseTheTargetDirectoryBase() {
        DirectoryConnection target = new DirectoryConnection();
        target.setBaseDn("dc=dst");

        assertThat(SyncPlacement.effectiveTargetBase(set("o=groups,c=us", "ou=Groups,dc=dst"), target))
                .isEqualTo("ou=Groups,dc=dst");
        // Blank target base: entries keep their source DNs, under the source scope.
        assertThat(SyncPlacement.effectiveTargetBase(set("o=groups,c=us", null), target)).isEqualTo("o=groups,c=us");
        assertThat(SyncPlacement.effectiveTargetBase(set(null, null), target)).isEqualTo("dc=dst");
    }

    @Test
    void aBlankTargetBase_placesEntriesAtTheirSourceDns() {
        assertThat(SyncPlacement.targetDn(set("o=groups,c=us", null), "cn=Eng,o=groups,c=us"))
                .isEqualTo("cn=Eng,o=groups,c=us");
    }
}
