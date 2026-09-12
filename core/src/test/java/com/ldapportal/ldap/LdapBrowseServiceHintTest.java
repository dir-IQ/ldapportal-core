// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.unboundid.ldap.sdk.Entry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link LdapBrowseService#hasChildrenHint(Entry)} — the
 * per-vendor subordinate-attribute reading that lets the directory browser
 * skip a probe search per child. Kept separate from
 * {@link LdapBrowseServiceTest} because these need no server or mocks.
 */
class LdapBrowseServiceHintTest {

    private static final String DN = "cn=x,dc=example,dc=com";

    @Test
    void hasSubordinates_booleanIsReadCaseInsensitively() {
        assertThat(LdapBrowseService.hasChildrenHint(entryWith("hasSubordinates", "TRUE"))).isTrue();
        assertThat(LdapBrowseService.hasChildrenHint(entryWith("hasSubordinates", "false"))).isFalse();
    }

    @Test
    void numSubordinates_positiveMeansChildren_zeroMeansLeaf() {
        assertThat(LdapBrowseService.hasChildrenHint(entryWith("numSubordinates", "12"))).isTrue();
        assertThat(LdapBrowseService.hasChildrenHint(entryWith("numSubordinates", "0"))).isFalse();
    }

    @Test
    void activeDirectoryApproximateCount_isHonoured() {
        assertThat(LdapBrowseService.hasChildrenHint(
                entryWith("msDS-Approx-Immed-Subordinates", "3"))).isTrue();
        assertThat(LdapBrowseService.hasChildrenHint(
                entryWith("msDS-Approx-Immed-Subordinates", "0"))).isFalse();
    }

    @Test
    void exactBooleanWinsOverDisagreeingCount() {
        Entry both = entryWith("hasSubordinates", "FALSE");
        both.setAttribute("numSubordinates", "5");
        assertThat(LdapBrowseService.hasChildrenHint(both)).isFalse();
    }

    @Test
    void unparseableCount_isIgnoredNotTreatedAsZero() {
        assertThat(LdapBrowseService.hasChildrenHint(entryWith("numSubordinates", "many"))).isNull();
    }

    @Test
    void noHintAttributes_returnsNullSoCallerProbes() {
        assertThat(LdapBrowseService.hasChildrenHint(new Entry(DN))).isNull();
    }

    private static Entry entryWith(String attr, String value) {
        Entry e = new Entry(DN);
        e.setAttribute(attr, value);
        return e;
    }
}
