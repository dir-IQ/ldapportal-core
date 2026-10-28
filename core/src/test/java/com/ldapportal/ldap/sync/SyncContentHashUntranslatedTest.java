// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.unboundid.ldap.sdk.Attribute;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Untranslated reference values are part of the content hash, so dropping one isn't skipped. */
class SyncContentHashUntranslatedTest {

    private static final List<Attribute> ATTRS = List.of(new Attribute("member", "uid=a,o=t"));

    @Test
    void anEntryWithNoneHashesAsBefore() {
        assertThat(SyncContentHash.of("cn=g,o=t", ATTRS, Map.of())).isEqualTo(SyncContentHash.of("cn=g,o=t", ATTRS));
    }

    @Test
    void addingOrRemovingAnUntranslatedValueChangesTheHash() {
        byte[] with = SyncContentHash.of("cn=g,o=t", ATTRS, Map.of("member", List.of("cn=stray,o=x")));
        assertThat(with).isNotEqualTo(SyncContentHash.of("cn=g,o=t", ATTRS));
        assertThat(with).isNotEqualTo(SyncContentHash.of("cn=g,o=t", ATTRS,
                Map.of("member", List.of("cn=other,o=x"))));
    }

    @Test
    void theirFormAndOrderDontMatter() {
        assertThat(SyncContentHash.of("cn=g,o=t", ATTRS, Map.of("Member", List.of("cn=B, o=x", "cn=a,o=x"))))
                .isEqualTo(SyncContentHash.of("cn=g,o=t", ATTRS, Map.of("member", List.of("cn=a,o=x", "cn=b,o=x"))));
    }

    @Test
    void theyCantCollideWithAProjectedAttributeOfTheSameName() {
        assertThat(SyncContentHash.of("cn=g,o=t", List.of(), Map.of("member", List.of("uid=a,o=t"))))
                .isNotEqualTo(SyncContentHash.of("cn=g,o=t", ATTRS));
    }
}
