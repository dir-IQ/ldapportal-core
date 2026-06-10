// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.validation;

import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.entity.enums.InputType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttributeSyntaxTest {

    // ── DN ──────────────────────────────────────────────────────────────────

    @Test
    void dnAcceptsWellFormedValue() {
        assertThatCode(() -> AttributeSyntax.require(
                AttributeSyntax.Kind.DN, "manager",
                "uid=boss,ou=people,dc=example,dc=com", DirectoryType.GENERIC))
                .doesNotThrowAnyException();
    }

    @Test
    void dnRejectsMalformedValueWithAttributeContext() {
        assertThatThrownBy(() -> AttributeSyntax.require(
                AttributeSyntax.Kind.DN, "manager", "not a dn", DirectoryType.GENERIC))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("manager")
                .hasMessageContaining("not a valid DN");
    }

    @Test
    void dnSkipsEntraId() {
        // Entra identifies objects by id/UPN, not DN — the shape check is a no-op.
        assertThatCode(() -> AttributeSyntax.require(
                AttributeSyntax.Kind.DN, "manager", "some-object-id", DirectoryType.ENTRA_ID))
                .doesNotThrowAnyException();
    }

    // ── EMAIL ───────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "jsmith@example.com",
            "j.smith+tag@sub.example.co.uk",
            "first_last@corp.internal"
    })
    void emailAcceptsWellFormedAddresses(String value) {
        assertThatCode(() -> AttributeSyntax.require(
                AttributeSyntax.Kind.EMAIL, "mail", value, DirectoryType.GENERIC))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-an-email",
            "missing-domain@",
            "@missing-local.com",
            "no-dot@domain",
            "two@@ats.com",
            "has space@example.com"
    })
    void emailRejectsMalformedAddresses(String value) {
        assertThatThrownBy(() -> AttributeSyntax.require(
                AttributeSyntax.Kind.EMAIL, "mail", value, DirectoryType.GENERIC))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mail")
                .hasMessageContaining("valid email");
    }

    // ── BOOLEAN ─────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"TRUE", "FALSE", "true", "false", "True"})
    void booleanAcceptsTrueAndFalseCaseInsensitive(String value) {
        assertThatCode(() -> AttributeSyntax.require(
                AttributeSyntax.Kind.BOOLEAN, "isMemberOf", value, DirectoryType.GENERIC))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"yes", "no", "1", "0", "T", "maybe"})
    void booleanRejectsOtherValues(String value) {
        assertThatThrownBy(() -> AttributeSyntax.require(
                AttributeSyntax.Kind.BOOLEAN, "isMemberOf", value, DirectoryType.GENERIC))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TRUE or FALSE");
    }

    // ── forInputType (shared with the client-facing hints) ──────────────────

    @Test
    void forInputTypeMapsDnLookupAndBoolean() {
        assertThat(AttributeSyntax.forInputType(InputType.DN_LOOKUP))
                .isEqualTo(AttributeSyntax.Kind.DN);
        assertThat(AttributeSyntax.forInputType(InputType.BOOLEAN))
                .isEqualTo(AttributeSyntax.Kind.BOOLEAN);
    }

    @Test
    void forInputTypeReturnsNullForShapelessTypes() {
        assertThat(AttributeSyntax.forInputType(InputType.TEXT)).isNull();
        assertThat(AttributeSyntax.forInputType(InputType.SELECT)).isNull();
        assertThat(AttributeSyntax.forInputType(null)).isNull();
    }
}
