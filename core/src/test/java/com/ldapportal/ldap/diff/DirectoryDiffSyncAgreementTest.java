// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.diff;

import com.ldapportal.ldap.sync.TargetEntryDiffer;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.schema.Schema;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The directory diff and the sync engine decide "same values?" with separate
 * code, on purpose: the diff is used to check sync's work, so it mustn't share
 * sync's bugs. This pins the two to the same answer on the cases that matter,
 * so neither can drift without a test noticing.
 *
 * <p>For each pair, sync sees no difference exactly when it wouldn't write the
 * attribute ({@link TargetEntryDiffer#diff} returns no modification).
 */
class DirectoryDiffSyncAgreementTest {

    private static final List<String> DN_ATTRS = List.of("member");

    static Stream<Arguments> cases() throws Exception {
        Schema schema = Schema.getDefaultStandardSchema();
        return Stream.of(
                Arguments.of("identical", new Attribute("cn", "Alice"), new Attribute("cn", "Alice"), null, true),
                Arguments.of("case-only change", new Attribute("cn", "Alice"), new Attribute("cn", "alice"), null, false),
                Arguments.of("trailing space", new Attribute("cn", "Alice"), new Attribute("cn", "Alice "), null, false),
                Arguments.of("value order", new Attribute("mail", "a@x", "b@x"), new Attribute("mail", "b@x", "a@x"),
                        null, true),
                Arguments.of("extra value", new Attribute("mail", "a@x"), new Attribute("mail", "a@x", "b@x"),
                        null, false),
                Arguments.of("dn spacing and case (named)", new Attribute("member", "UID=Alice, OU=People,DC=x"),
                        new Attribute("member", "uid=alice,ou=people,dc=x"), null, true),
                Arguments.of("dn spacing and case (schema)", new Attribute("seeAlso", "CN=A, DC=x"),
                        new Attribute("seeAlso", "cn=a,dc=x"), schema, true),
                Arguments.of("different dn", new Attribute("member", "uid=alice,dc=x"),
                        new Attribute("member", "uid=bob,dc=x"), null, false),
                Arguments.of("invalid dn, same text", new Attribute("member", "not a dn"),
                        new Attribute("member", "not a dn"), null, true),
                Arguments.of("invalid dn, case differs", new Attribute("member", "not a dn"),
                        new Attribute("member", "Not a DN"), null, false),
                Arguments.of("non-dn attribute with schema", new Attribute("description", "X"),
                        new Attribute("description", "x"), schema, false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void diffAndSyncAgree(String name, Attribute a, Attribute b, Schema schema, boolean expectedSame) {
        boolean diffSame = DiffValueComparison.values(a, b, schema, DN_ATTRS, UnaryOperator.identity()).same();

        Entry target = new Entry("cn=t,dc=x", b);
        List<Modification> mods = TargetEntryDiffer.diff(target, List.of(a), Set.of(), schema, DN_ATTRS);
        boolean syncSame = mods.isEmpty();

        assertThat(diffSame).as("directory diff").isEqualTo(expectedSame);
        assertThat(syncSame).as("sync engine").isEqualTo(expectedSame);
    }
}
