// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.validation;

import com.ldapportal.entity.enums.DirectoryType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class DnValidatorTest {

    // ── isValidDn / parse ───────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "uid=jsmith,ou=people,dc=example,dc=com",
            "cn=Admins,ou=groups,dc=corp",
            "dc=example,dc=com",
            "cn=a+sn=b,dc=example,dc=com",          // multi-valued RDN
            "cn=Smith\\, John,ou=people,dc=example,dc=com" // escaped comma in value
    })
    void acceptsWellFormedDns(String dn) {
        assertThat(DnValidator.isValidDn(dn)).isTrue();
        assertThatCode(() -> DnValidator.parse(dn)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not a dn",
            "uid=jsmith,,dc=com",     // empty RDN
            "=jsmith,dc=com",         // missing attribute
            "uid",                    // bare attribute, no value
            "uid=jsmith,ou"           // trailing bare component
    })
    void rejectsMalformedDns(String dn) {
        assertThat(DnValidator.isValidDn(dn)).isFalse();
        assertThatThrownBy(() -> DnValidator.parse(dn))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid DN");
    }

    @Test
    void rejectsNullAndBlank() {
        assertThat(DnValidator.isValidDn(null)).isFalse();
        assertThat(DnValidator.isValidDn("   ")).isFalse();
        assertThatThrownBy(() -> DnValidator.parse(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty");
        assertThatThrownBy(() -> DnValidator.parse("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty");
    }

    @Test
    void rejectsRootDse() {
        // The empty DN parses but addresses the root DSE — not a valid target.
        assertThat(DnValidator.isValidDn("")).isFalse();
        assertThatThrownBy(() -> DnValidator.parse(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── requireValidDn (directory-type aware) ───────────────────────────────

    @Test
    void requireValidDnThrowsForMalformedOnLdapDirectory() {
        assertThatThrownBy(() ->
                DnValidator.requireValidDn("not a dn", DirectoryType.GENERIC))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requireValidDnSkipsEntraId() {
        // Entra identifies objects by id/UPN, not DN — syntax check is a no-op.
        assertThatCode(() ->
                DnValidator.requireValidDn("not-a-dn-just-an-object-id", DirectoryType.ENTRA_ID))
                .doesNotThrowAnyException();
    }

    // ── requireValidRdn ─────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"uid=jsmith", "cn=Engineering", "cn=a+sn=b"})
    void acceptsWellFormedRdns(String rdn) {
        assertThatCode(() ->
                DnValidator.requireValidRdn(rdn, DirectoryType.GENERIC))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"uid", "=jsmith", "not an rdn"})
    void rejectsMalformedRdns(String rdn) {
        assertThatThrownBy(() ->
                DnValidator.requireValidRdn(rdn, DirectoryType.GENERIC))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid RDN");
    }

    @Test
    void requireValidRdnRejectsBlank() {
        assertThatThrownBy(() ->
                DnValidator.requireValidRdn("  ", DirectoryType.GENERIC))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty");
    }

    @Test
    void requireValidRdnSkipsEntraId() {
        assertThatCode(() ->
                DnValidator.requireValidRdn("anything", DirectoryType.ENTRA_ID))
                .doesNotThrowAnyException();
    }
}
