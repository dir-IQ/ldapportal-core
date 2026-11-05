// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.converter.DnListConverter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DirectoryRootsTest {

    private static DirectoryConnection dc(String base, String... more) {
        DirectoryConnection d = new DirectoryConnection();
        d.setBaseDn(base);
        d.setAdditionalBaseDns(new ArrayList<>(List.of(more)));
        return d;
    }

    @Test
    void baseDns_primaryFirst_blanksAndCaseInsensitiveDuplicatesDropped() {
        DirectoryConnection d = dc("dc=example,dc=com", "o=partners", " ", "DC=Example,DC=Com", "o=Partners");

        assertThat(d.getBaseDns()).containsExactly("dc=example,dc=com", "o=partners");
        assertThat(dc("dc=example,dc=com").getBaseDns()).containsExactly("dc=example,dc=com");
        assertThat(DirectoryRoots.isMultiRoot(d)).isTrue();
        assertThat(DirectoryRoots.isMultiRoot(dc("dc=example,dc=com"))).isFalse();
    }

    @Test
    void rootOf_matchesStructurally_andPicksTheDeepestRoot() {
        DirectoryConnection d = dc("dc=example,dc=com", "o=partners", "secAuthority=Default");

        assertThat(DirectoryRoots.rootOf(d, "uid=a, OU=People, DC=example, DC=com")).isEqualTo("dc=example,dc=com");
        assertThat(DirectoryRoots.rootOf(d, "o=partners")).isEqualTo("o=partners");
        assertThat(DirectoryRoots.rootOf(d, "principalName=x,cn=Users,secAuthority=Default"))
                .isEqualTo("secAuthority=Default");
        assertThat(DirectoryRoots.rootOf(d, "uid=a,o=elsewhere")).isNull();
        assertThat(DirectoryRoots.isWithin(d, "uid=a,o=elsewhere")).isFalse();
    }

    @Test
    void parentWithinRoots_isNullForARoot() {
        DirectoryConnection d = dc("dc=example,dc=com", "o=partners");

        assertThat(DirectoryRoots.parentWithinRoots(d, "o=partners")).isNull();
        assertThat(DirectoryRoots.parentWithinRoots(d, "dc=example,dc=com")).isNull();
        assertThat(DirectoryRoots.parentWithinRoots(d, "uid=carol,o=partners")).isEqualTo("o=partners");
        assertThat(DirectoryRoots.parentWithinRoots(d, "uid=a,ou=people,dc=example,dc=com"))
                .isEqualTo("ou=people,dc=example,dc=com");
    }

    @Test
    void nests_isTrueForTheSameOrAnAncestorDn() {
        assertThat(DirectoryRoots.nests("dc=example,dc=com", "DC=Example,DC=Com")).isTrue();
        assertThat(DirectoryRoots.nests("ou=x,dc=example,dc=com", "dc=example,dc=com")).isTrue();
        assertThat(DirectoryRoots.nests("dc=example,dc=com", "ou=x,dc=example,dc=com")).isTrue();
        assertThat(DirectoryRoots.nests("dc=example,dc=com", "o=partners")).isFalse();
    }

    @Test
    void dnListConverter_roundTripsOneDnPerLine() {
        DnListConverter c = new DnListConverter();

        String stored = c.convertToDatabaseColumn(List.of("o=partners", " secAuthority=Default ", ""));
        assertThat(stored).isEqualTo("o=partners\nsecAuthority=Default");
        assertThat(c.convertToEntityAttribute(stored)).containsExactly("o=partners", "secAuthority=Default");
        assertThat(c.convertToDatabaseColumn(List.of())).isNull();
        assertThat(c.convertToEntityAttribute(null)).isEmpty();
    }
}
