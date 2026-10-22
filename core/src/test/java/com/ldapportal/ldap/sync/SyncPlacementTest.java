// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.entity.SyncSet;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SyncPlacementTest {

    private static SyncSet set(String sourceBase, String targetBase) {
        SyncSet s = new SyncSet();
        s.setObjectScopeBaseDn(sourceBase);
        s.setTargetBaseDn(targetBase);
        return s;
    }

    @Test
    void keepsTheSourceRdnsAsWritten_underTheTargetBase() {
        // The RDNs below the scope keep their case, so DN references rewritten
        // to this entry (member, secDN) read exactly as on the source.
        assertThat(SyncPlacement.targetDn(set("o=groups,c=us", "o=Groups,dc=dst"),
                "cn=IVIA_Import_Test,ou=Team A,O=Groups,C=US"))
                .isEqualTo("cn=IVIA_Import_Test,ou=Team A,o=Groups,dc=dst");
    }

    @Test
    void tidiesOnlyTheSpacingBetweenRdns() {
        assertThat(SyncPlacement.targetDn(set("dc=src", "dc=dst"), "uid=JSmith, ou=People, dc=src"))
                .isEqualTo("uid=JSmith,ou=People,dc=dst");
    }

    @Test
    void theScopeBaseItselfMapsToTheTargetBase() {
        assertThat(SyncPlacement.targetDn(set("ou=People,dc=src", "ou=Users,dc=dst"), "OU=people,DC=src"))
                .isEqualTo("ou=Users,dc=dst");
    }

    @Test
    void outsideTheScope_isUnplaceable_andWithoutBasesTheDnIsKept() {
        assertThat(SyncPlacement.targetDn(set("ou=People,dc=src", "ou=Users,dc=dst"), "uid=a,ou=Other,dc=src"))
                .isNull();
        assertThat(SyncPlacement.targetDn(set(null, null), "uid=A,dc=src")).isEqualTo("uid=A,dc=src");
    }

    @Test
    void sameDn_ignoresCaseAndSpacing() {
        assertThat(SyncDnUtil.sameDn("uid=a,ou=people,dc=dst", "UID=A, OU=People,DC=dst")).isTrue();
        assertThat(SyncDnUtil.sameDn("uid=a,dc=dst", "uid=b,dc=dst")).isFalse();
        assertThat(SyncDnUtil.sameDn(null, null)).isTrue();
        assertThat(SyncDnUtil.sameDn("uid=a,dc=dst", null)).isFalse();
    }
}
