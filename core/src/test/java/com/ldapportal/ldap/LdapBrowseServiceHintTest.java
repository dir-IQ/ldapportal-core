// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.Filter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests for the connection-free helpers on
 * {@link LdapBrowseService}: {@code hasChildrenHint} (per-vendor
 * subordinate-attribute reading) and {@code buildChildFilter} (branch
 * filter text → LDAP filter). Kept separate from
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

    // ── buildChildFilter ─────────────────────────────────────────────────────

    @Test
    void blankFilter_meansNoFilter() {
        assertThat(LdapBrowseService.buildChildFilter(null)).isNull();
        assertThat(LdapBrowseService.buildChildFilter("   ")).isNull();
    }

    @Test
    void rawFilter_isParsedVerbatim() throws Exception {
        Filter f = LdapBrowseService.buildChildFilter(" (&(objectClass=person)(cn=a*)) ");
        assertThat(f).isEqualTo(Filter.create("(&(objectClass=person)(cn=a*))"));
    }

    @Test
    void invalidRawFilter_isAnIllegalArgument() {
        assertThatThrownBy(() -> LdapBrowseService.buildChildFilter("(cn="))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid LDAP filter");
    }

    @Test
    void plainText_becomesSubstringOrAcrossNamingAttributes_withEscaping() {
        Filter f = LdapBrowseService.buildChildFilter("smi(th)*");

        assertThat(f.getFilterType()).isEqualTo(Filter.FILTER_TYPE_OR);
        assertThat(f.getComponents()).hasSize(LdapBrowseService.QUICK_FILTER_ATTRS.size());
        for (Filter component : f.getComponents()) {
            assertThat(component.getFilterType()).isEqualTo(Filter.FILTER_TYPE_SUBSTRING);
            assertThat(component.getSubAnyStrings()).containsExactly("smi(th)*");
        }
        // The rendered filter escapes the metacharacters, so the text is
        // matched literally instead of being interpreted.
        assertThat(f.toString()).contains("(cn=*smi\\28th\\29\\2a*)");
    }

    private static Entry entryWith(String attr, String value) {
        Entry e = new Entry(DN);
        e.setAttribute(attr, value);
        return e;
    }
}
