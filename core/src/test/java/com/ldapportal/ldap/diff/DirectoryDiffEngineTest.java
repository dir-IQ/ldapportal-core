// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.diff;

import com.ldapportal.entity.enums.DirectoryDiffCategory;
import com.ldapportal.entity.enums.DirectoryDiffPairing;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class DirectoryDiffEngineTest {

    private static final String BASE_A = "ou=people,dc=a";
    private static final String BASE_B = "ou=Users,dc=b";

    private static Entry person(String dn, String... attrs) {
        Entry e = new Entry(dn, new Attribute("objectClass", "inetOrgPerson"));
        for (int i = 0; i < attrs.length; i += 2) {
            e.addAttribute(attrs[i], attrs[i + 1]);
        }
        return e;
    }

    private static DirectoryDiffEngine.Options byDn(Predicate<String> compared) {
        return new DirectoryDiffEngine.Options(DirectoryDiffPairing.DN, null, compared, null,
                DirectoryDiffEngine.rebase(BASE_A, BASE_B));
    }

    private static List<DirectoryDiffEngine.Result> run(List<Entry> a, List<Entry> b,
                                                        DirectoryDiffEngine.Options options) {
        List<DirectoryDiffEngine.Result> out = new ArrayList<>();
        AtomicInteger progress = new AtomicInteger();
        boolean done = DirectoryDiffEngine.compare(a, b, options, out::add, progress::addAndGet, () -> false);
        assertThat(done).isTrue();
        assertThat(progress.get()).isEqualTo(a.size() + b.size());
        return out;
    }

    @Test
    void pairsByDnAcrossDifferentBases() {
        List<Entry> a = List.of(
                person("uid=alice," + BASE_A, "cn", "Alice", "mail", "alice@x"),
                person("uid=bob," + BASE_A, "cn", "Bob"),
                person("uid=carol," + BASE_A, "cn", "Carol"));
        List<Entry> b = List.of(
                person("UID=Alice," + BASE_B, "cn", "Alice", "mail", "alice@x"),
                person("uid=bob," + BASE_B, "cn", "Robert"),
                person("uid=dave," + BASE_B, "cn", "Dave"));

        List<DirectoryDiffEngine.Result> results = run(a, b, byDn(DirectoryDiffEngine.compared(null, null)));

        assertThat(results).extracting(DirectoryDiffEngine.Result::category).containsExactly(
                DirectoryDiffCategory.SAME, DirectoryDiffCategory.DIFFERENT,
                DirectoryDiffCategory.ONLY_A, DirectoryDiffCategory.ONLY_B);
        DirectoryDiffEngine.Result bob = results.get(1);
        assertThat(bob.diffs()).singleElement().satisfies(d -> {
            assertThat(d.name()).isEqualTo("cn");
            assertThat(d.onlyA()).containsExactly("Bob");
            assertThat(d.onlyB()).containsExactly("Robert");
        });
        assertThat(bob.same()).extracting(DirectoryDiffEngine.SameAttribute::name).contains("objectClass");
        assertThat(results.get(3).dnB()).isEqualTo("uid=dave," + BASE_B);
    }

    @Test
    void entryOutsideSideABaseIsOnlyAWithNote() {
        List<DirectoryDiffEngine.Result> results = run(List.of(person("uid=x,ou=other,dc=a")), List.of(),
                byDn(DirectoryDiffEngine.compared(null, null)));
        assertThat(results).singleElement().satisfies(r -> {
            assertThat(r.category()).isEqualTo(DirectoryDiffCategory.ONLY_A);
            assertThat(r.note()).isEqualTo("Not under side A's base DN");
        });
    }

    @Test
    void dnValuesCompareAfterRebasing() {
        Entry ga = new Entry("cn=g," + BASE_A, new Attribute("member", "uid=alice," + BASE_A));
        Entry gb = new Entry("cn=g," + BASE_B, new Attribute("member", "uid=alice," + BASE_B));
        DirectoryDiffEngine.Result r = DirectoryDiffEngine.comparePair(ga, gb,
                byDn(DirectoryDiffEngine.compared(null, null)));
        assertThat(r.category()).isEqualTo(DirectoryDiffCategory.SAME);
    }

    @Test
    void pairsByAttributeWithMissingAndSharedKeys() {
        DirectoryDiffEngine.Options options = new DirectoryDiffEngine.Options(DirectoryDiffPairing.ATTRIBUTE,
                "employeeNumber", DirectoryDiffEngine.compared(null, null), null,
                DirectoryDiffEngine.rebase(BASE_A, BASE_B));
        List<Entry> a = List.of(
                person("uid=alice," + BASE_A, "employeeNumber", "E1", "cn", "Alice"),
                person("uid=nokey," + BASE_A, "cn", "No key"),
                person("uid=dup1," + BASE_A, "employeeNumber", "E9"),
                person("uid=dup2," + BASE_A, "employeeNumber", "E9"));
        List<Entry> b = List.of(
                person("cn=Alice Smith,ou=elsewhere,dc=b", "employeeNumber", "e1", "cn", "Alice"),
                person("uid=dup," + BASE_B, "employeeNumber", "E9"));

        List<DirectoryDiffEngine.Result> results = run(a, b, options);

        assertThat(results.get(0).category()).isEqualTo(DirectoryDiffCategory.DIFFERENT); // employeeNumber case
        assertThat(results.get(0).dnB()).isEqualTo("cn=Alice Smith,ou=elsewhere,dc=b");
        assertThat(results.get(1).note()).isEqualTo("No employeeNumber value to pair by");
        assertThat(results.get(2).note()).isEqualTo(
                "Its employeeNumber value is shared by 2 entries on A and 1 on B, so it can't pair");
        assertThat(results.get(4).category()).isEqualTo(DirectoryDiffCategory.ONLY_B);
        assertThat(results.get(4).note()).contains("shared by 2 entries on A and 1 on B");
    }

    @Test
    void comparedLeavesOutOperationalSecretsAndIgnored() {
        Predicate<String> all = DirectoryDiffEngine.compared(null, List.of("Description"));
        assertThat(all.test("cn")).isTrue();
        assertThat(all.test("userpassword")).isFalse();
        assertThat(all.test("description")).isFalse();
        assertThat(all.test("modifytimestamp")).isFalse();

        Predicate<String> only = DirectoryDiffEngine.compared(List.of("mail", "userPassword"), List.of());
        assertThat(only.test("mail")).isTrue();
        assertThat(only.test("cn")).isFalse();
        assertThat(only.test("userpassword")).isFalse();
    }

    @Test
    void valuesAreCappedButCounted() {
        Entry ga = new Entry("cn=g," + BASE_A);
        Entry gb = new Entry("cn=g," + BASE_B);
        String[] many = new String[DirectoryDiffEngine.VALUE_CAP + 5];
        for (int i = 0; i < many.length; i++) {
            many[i] = "v" + i;
        }
        ga.addAttribute("description", many);
        DirectoryDiffEngine.Result r = DirectoryDiffEngine.comparePair(ga, gb,
                byDn(DirectoryDiffEngine.compared(null, null)));
        assertThat(r.diffs()).singleElement().satisfies(d -> {
            assertThat(d.onlyA()).hasSize(DirectoryDiffEngine.VALUE_CAP);
            assertThat(d.onlyACount()).isEqualTo(many.length);
        });
    }

    @Test
    void stopsWhenCancelled() {
        List<DirectoryDiffEngine.Result> out = new ArrayList<>();
        boolean done = DirectoryDiffEngine.compare(List.of(person("uid=a," + BASE_A)), List.of(),
                byDn(DirectoryDiffEngine.compared(null, null)), out::add, n -> { }, () -> true);
        assertThat(done).isFalse();
        assertThat(out).isEmpty();
    }

    @Test
    void rebaseSwapsBaseAndRejectsOutsiders() {
        var r = DirectoryDiffEngine.rebase(BASE_A, BASE_B);
        assertThat(r.apply("uid=a,ou=x," + BASE_A)).isEqualTo("uid=a,ou=x," + BASE_B);
        assertThat(r.apply(BASE_A)).isEqualTo(BASE_B);
        assertThat(r.apply("uid=a,dc=elsewhere")).isNull();
        assertThat(r.apply("garbage")).isNull();
    }
}
