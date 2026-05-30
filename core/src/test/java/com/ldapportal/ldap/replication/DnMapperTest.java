// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.entity.ReplicationLink;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.LDAPException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DnMapperTest {

    @Test
    void identityMapping_returnsSourceDnVerbatim() {
        // Null source/target base = identity mapping. The mapper must
        // not touch the DN at all — preserves whitespace and case
        // exactly so a caller passing the same DN to the source and
        // the target sees the same bytes on both sides.
        ReplicationLink link = link(null, null);
        assertThat(DnMapper.map("uid=alice,ou=people,dc=corp,dc=com", link))
                .isEqualTo("uid=alice,ou=people,dc=corp,dc=com");
    }

    @Test
    void baseDnSubstitution_rewritesSuffix() {
        ReplicationLink link = link("dc=src,dc=com", "dc=tgt,dc=com");
        String mapped = DnMapper.map("uid=alice,ou=people,dc=src,dc=com", link);
        assertThat(mapped).isEqualTo("uid=alice,ou=people,dc=tgt,dc=com");
    }

    @Test
    void baseDnSubstitution_dnIsBase_returnsTargetBase() {
        // Edge case: the source DN IS the base. The mapped target is
        // the target base itself, not '<empty>,<target>'.
        ReplicationLink link = link("dc=src,dc=com", "dc=tgt,dc=com");
        assertThat(DnMapper.map("dc=src,dc=com", link)).isEqualTo("dc=tgt,dc=com");
    }

    @Test
    void outOfScopeDn_returnsNull() {
        // DN is not under the source base — replication should skip.
        ReplicationLink link = link("dc=src,dc=com", "dc=tgt,dc=com");
        assertThat(DnMapper.map("uid=alice,dc=other,dc=com", link)).isNull();
    }

    @Test
    void caseInsensitiveBaseDnMatching() throws LDAPException {
        // OUD / OpenDJ canonicalise stored DNs lowercase; operator
        // configuration can be mixed-case. The mapper compares
        // canonically, so a mixed-case source-base configuration
        // still matches a lowercase source DN. The output preserves
        // the configured target base case verbatim (operators see
        // what they typed), so we assert the structural relationship
        // via DN equality rather than substring match.
        ReplicationLink link = link("DC=src,DC=com", "DC=tgt,DC=com");
        String mapped = DnMapper.map("uid=alice,ou=people,dc=src,dc=com", link);
        assertThat(mapped).isNotNull();
        assertThat(new DN(mapped)).isEqualTo(
                new DN("uid=alice,ou=people,dc=tgt,dc=com"));
    }

    @Test
    void unparseableDn_returnsSourceDnAsFallback() {
        // Malformed DN: rather than throwing, fall back to identity so
        // the caller's failure mode is "delivery fails at the target"
        // — a visible error — rather than "wrote to the wrong place".
        ReplicationLink link = link("dc=src,dc=com", "dc=tgt,dc=com");
        assertThat(DnMapper.map("this is not a dn", link)).isEqualTo("this is not a dn");
    }

    private static ReplicationLink link(String sourceBaseDn, String targetBaseDn) {
        ReplicationLink l = new ReplicationLink();
        l.setSourceBaseDn(sourceBaseDn);
        l.setTargetBaseDn(targetBaseDn);
        return l;
    }
}
