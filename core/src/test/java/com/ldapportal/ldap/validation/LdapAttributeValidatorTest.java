// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.validation;

import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.entity.enums.InputType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LdapAttributeValidatorTest {

    private static Map<String, List<String>> attrs(String name, String... values) {
        return Map.of(name, List.of(values));
    }

    // ── well-known attributes (no profile config) ───────────────────────────

    @Test
    void rejectsMalformedWellKnownDnAttribute() {
        assertThatThrownBy(() -> LdapAttributeValidator.validateSyntax(
                DirectoryType.GENERIC, attrs("manager", "not a dn"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("manager");
    }

    @Test
    void acceptsWellFormedWellKnownDnAttribute() {
        assertThatCode(() -> LdapAttributeValidator.validateSyntax(
                DirectoryType.GENERIC,
                attrs("manager", "uid=boss,ou=people,dc=example,dc=com"), Map.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMalformedMail() {
        assertThatThrownBy(() -> LdapAttributeValidator.validateSyntax(
                DirectoryType.GENERIC, attrs("mail", "nope"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mail");
    }

    @Test
    void passesThroughUnconstrainedAttributes() {
        // cn / description have no intrinsic syntax — never rejected here.
        assertThatCode(() -> LdapAttributeValidator.validateSyntax(
                DirectoryType.GENERIC,
                Map.of("cn", List.of("anything goes"),
                       "description", List.of("free <text> 123")),
                Map.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void validatesEveryValueOfAMultiValuedAttribute() {
        assertThatThrownBy(() -> LdapAttributeValidator.validateSyntax(
                DirectoryType.GENERIC,
                attrs("member", "uid=ok,dc=example,dc=com", "bad dn"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── profile inputType resolution ────────────────────────────────────────

    @Test
    void inputTypeDnLookupForcesDnCheckOnArbitraryAttribute() {
        // A profile field flagged DN_LOOKUP is DN-checked even with a non-well-known name.
        Map<String, InputType> types = Map.of("assistantref", InputType.DN_LOOKUP);
        assertThatThrownBy(() -> LdapAttributeValidator.validateSyntax(
                DirectoryType.GENERIC, attrs("assistantRef", "not a dn"), types))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assistantRef");
    }

    @Test
    void inputTypeBooleanForcesBooleanCheck() {
        Map<String, InputType> types = Map.of("activeflag", InputType.BOOLEAN);
        assertThatThrownBy(() -> LdapAttributeValidator.validateSyntax(
                DirectoryType.GENERIC, attrs("activeFlag", "maybe"), types))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TRUE or FALSE");
    }

    // ── directory-type awareness & edge cases ───────────────────────────────

    @Test
    void skipsDnCheckForEntraId() {
        assertThatCode(() -> LdapAttributeValidator.validateSyntax(
                DirectoryType.ENTRA_ID, attrs("manager", "an-object-id"), Map.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void ignoresBlankAndEmptyValues() {
        assertThatCode(() -> LdapAttributeValidator.validateSyntax(
                DirectoryType.GENERIC,
                Map.of("manager", List.of("   "), "mail", List.of()),
                Map.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void toleratesNullAndEmptyAttributeMap() {
        assertThatCode(() -> LdapAttributeValidator.validateSyntax(
                DirectoryType.GENERIC, null, Map.of()))
                .doesNotThrowAnyException();
        assertThatCode(() -> LdapAttributeValidator.validateSyntax(
                DirectoryType.GENERIC, Map.of(), null))
                .doesNotThrowAnyException();
    }
}
