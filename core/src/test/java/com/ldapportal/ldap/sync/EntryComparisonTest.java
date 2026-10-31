// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.unboundid.ldap.sdk.Attribute;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

class EntryComparisonTest {

    private static final List<String> DN_ATTRS = List.of("member");

    @Test
    void dnValuesCompareCanonically() {
        EntryComparison.ValueDiff d = EntryComparison.values(
                new Attribute("member", "UID=Alice, OU=People,DC=x"),
                new Attribute("member", "uid=alice,ou=people,dc=x"),
                null, DN_ATTRS, UnaryOperator.identity());
        assertThat(d.same()).isTrue();
        assertThat(d.both()).containsExactly("UID=Alice, OU=People,DC=x");
    }

    @Test
    void otherValuesCompareExactly() {
        EntryComparison.ValueDiff d = EntryComparison.values(
                new Attribute("cn", "Alice"), new Attribute("cn", "alice"), null, DN_ATTRS, UnaryOperator.identity());
        assertThat(d.same()).isFalse();
        assertThat(d.onlyA()).containsExactly("Alice");
        assertThat(d.onlyB()).containsExactly("alice");
    }

    @Test
    void multiValuedSplitsIntoOnlyAOnlyBAndBoth() {
        EntryComparison.ValueDiff d = EntryComparison.values(
                new Attribute("mail", "a@x", "b@x", "c@x"), new Attribute("mail", "b@x", "c@x", "d@x"),
                null, DN_ATTRS, UnaryOperator.identity());
        assertThat(d.onlyA()).containsExactly("a@x");
        assertThat(d.onlyB()).containsExactly("d@x");
        assertThat(d.both()).containsExactly("b@x", "c@x");
    }

    @Test
    void mapARewritesSideADnValuesBeforeComparing() {
        UnaryOperator<String> rebase = v -> v.replace("dc=a", "dc=b");
        EntryComparison.ValueDiff d = EntryComparison.values(
                new Attribute("member", "uid=alice,dc=a"), new Attribute("member", "uid=alice,dc=b"),
                null, DN_ATTRS, rebase);
        assertThat(d.same()).isTrue();
        // reported as side A writes it
        assertThat(d.both()).containsExactly("uid=alice,dc=a");
    }

    @Test
    void invalidDnFallsBackToExactComparison() {
        EntryComparison.ValueDiff d = EntryComparison.values(
                new Attribute("member", "not a dn"), new Attribute("member", "not a dn"),
                null, DN_ATTRS, UnaryOperator.identity());
        assertThat(d.same()).isTrue();
    }
}
