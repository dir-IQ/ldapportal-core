// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.validation;

import com.ldapportal.entity.enums.DirectoryType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NamingAttributesTest {

    @Test
    void addsMissingNamingValue() {
        Map<String, List<String>> merged = NamingAttributes.mergeRdnValues(
                "uid=jsmith,ou=people,dc=example,dc=com",
                Map.of("cn", List.of("John Smith")),
                DirectoryType.OPENLDAP);

        assertThat(merged)
                .containsEntry("uid", List.of("jsmith"))
                .containsEntry("cn", List.of("John Smith"));
    }

    @Test
    void addsEveryComponentOfAMultiValuedRdn() {
        // The o=0001+cn=Sanjay Mishra case: both AVAs are naming values and
        // both must be present on the entry.
        Map<String, List<String>> merged = NamingAttributes.mergeRdnValues(
                "o=0001+cn=Sanjay Mishra,ou=People,dc=oud1,dc=example,dc=com",
                Map.of("mail", List.of("sm@example.com")),
                DirectoryType.ORACLE_UNIFIED_DIRECTORY);

        assertThat(merged)
                .containsEntry("o", List.of("0001"))
                .containsEntry("cn", List.of("Sanjay Mishra"))
                .containsEntry("mail", List.of("sm@example.com"));
    }

    @Test
    void leavesAttributesAloneWhenValuePresentCaseInsensitively() {
        // Attribute name and value matching are case-insensitive; the map's
        // own key case is preserved.
        Map<String, List<String>> merged = NamingAttributes.mergeRdnValues(
                "cn=sanjay mishra,ou=People,dc=example,dc=com",
                Map.of("CN", List.of("Sanjay Mishra")),
                DirectoryType.OPENLDAP);

        assertThat(merged).containsOnlyKeys("CN");
        assertThat(merged.get("CN")).containsExactly("Sanjay Mishra");
    }

    @Test
    void appendsNamingValueWhenAttributeHasOtherValues() {
        Map<String, List<String>> merged = NamingAttributes.mergeRdnValues(
                "cn=Primary Name,ou=people,dc=example,dc=com",
                Map.of("cn", List.of("Other Name")),
                DirectoryType.OPENLDAP);

        assertThat(merged.get("cn")).containsExactly("Other Name", "Primary Name");
    }

    @Test
    void unescapesRdnValues() {
        Map<String, List<String>> merged = NamingAttributes.mergeRdnValues(
                "cn=Mishra\\, Sanjay,ou=people,dc=example,dc=com",
                Map.of(),
                DirectoryType.OPENLDAP);

        assertThat(merged).containsEntry("cn", List.of("Mishra, Sanjay"));
    }

    @Test
    void entraIdIsANoOp() {
        Map<String, List<String>> attrs = Map.of("displayName", List.of("X"));

        assertThat(NamingAttributes.mergeRdnValues("not-a-dn", attrs, DirectoryType.ENTRA_ID))
                .isSameAs(attrs);
    }

    @Test
    void malformedDnThrows() {
        assertThatThrownBy(() -> NamingAttributes.mergeRdnValues(
                "not a valid dn", Map.of(), DirectoryType.OPENLDAP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid DN");
    }
}
