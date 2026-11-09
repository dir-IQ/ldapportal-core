// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DnContainersTest {

    @Test
    void singleEntry_isItsParent() {
        assertThat(DnContainers.commonContainer(List.of("uid=a,ou=People,dc=example,dc=com")))
                .isEqualTo("ou=People,dc=example,dc=com");
    }

    @Test
    void siblings_shareTheirParent() {
        assertThat(DnContainers.commonContainer(List.of(
                "uid=a,ou=People,dc=example,dc=com",
                "uid=b,ou=People,dc=example,dc=com")))
                .isEqualTo("ou=People,dc=example,dc=com");
    }

    @Test
    void entriesInDifferentOus_climbToTheClosestCommonAncestor() {
        assertThat(DnContainers.commonContainer(List.of(
                "uid=a,ou=Staff,ou=People,dc=example,dc=com",
                "uid=b,ou=Contractors,ou=People,dc=example,dc=com",
                "uid=c,ou=Interns,ou=Staff,ou=People,dc=example,dc=com")))
                .isEqualTo("ou=People,dc=example,dc=com");
    }

    @Test
    void matchesAttributeNamesCaseInsensitively() {
        assertThat(DnContainers.commonContainer(List.of(
                "uid=a,ou=People,dc=example,dc=com",
                "UID=b,OU=People,DC=example,DC=com")))
                .isEqualToIgnoringCase("ou=People,dc=example,dc=com");
    }

    @Test
    void noContainerToName_isNull() {
        assertThat(DnContainers.commonContainer(List.of())).isNull();
        assertThat(DnContainers.commonContainer(Arrays.asList(null, " "))).isNull();
        // No shared ancestor.
        assertThat(DnContainers.commonContainer(List.of(
                "uid=a,ou=People,dc=example,dc=com", "uid=b,ou=People,dc=other,dc=org"))).isNull();
        // Top-level entry: nothing above it.
        assertThat(DnContainers.commonContainer(List.of("dc=com"))).isNull();
        // Not LDAP DN syntax (e.g. an Entra object id).
        assertThat(DnContainers.commonContainer(List.of("6f1c2a7e-1b2c-4d5e-8f90-123456789abc"))).isNull();
    }
}
