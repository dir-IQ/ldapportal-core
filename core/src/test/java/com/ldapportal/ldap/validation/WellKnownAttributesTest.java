// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WellKnownAttributesTest {

    @ParameterizedTest
    @ValueSource(strings = {"manager", "Manager", "MANAGER", "secretary", "owner",
            "seeAlso", "roleOccupant", "member", "uniqueMember"})
    void dnValuedAttributesResolveToDnCaseInsensitively(String attribute) {
        assertThat(WellKnownAttributes.syntaxFor(attribute))
                .isEqualTo(AttributeSyntax.Kind.DN);
    }

    @Test
    void mailResolvesToEmail() {
        assertThat(WellKnownAttributes.syntaxFor("mail"))
                .isEqualTo(AttributeSyntax.Kind.EMAIL);
        assertThat(WellKnownAttributes.syntaxFor("MAIL"))
                .isEqualTo(AttributeSyntax.Kind.EMAIL);
    }

    @Test
    void unknownAndNullAttributesHaveNoSyntax() {
        assertThat(WellKnownAttributes.syntaxFor("cn")).isNull();
        assertThat(WellKnownAttributes.syntaxFor("description")).isNull();
        assertThat(WellKnownAttributes.syntaxFor(null)).isNull();
    }

    @Test
    void allExposesTheImmutableMapForUiMirroring() {
        var all = WellKnownAttributes.all();
        assertThat(all)
                .containsEntry("manager", AttributeSyntax.Kind.DN)
                .containsEntry("mail", AttributeSyntax.Kind.EMAIL);
        // Keys are lower-case and the map is the source of truth for syntaxFor().
        assertThat(all.keySet()).allMatch(k -> k.equals(k.toLowerCase(java.util.Locale.ROOT)));
        assertThatThrownBy(() -> all.put("foo", AttributeSyntax.Kind.DN))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
