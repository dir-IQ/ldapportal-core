// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

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
}
