// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.dto.sync.SyncDriftItem;
import com.ldapportal.dto.sync.SyncVerifyResult;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the pure source/target comparison
 * ({@link SyncContentVerifier#compare}). The LDAP enumeration is exercised
 * end-to-end elsewhere; this pins the classification of in-sync / missing /
 * orphan / drifted entries, which is the heart of the verification.
 */
class SyncContentVerifierTest {

    private static final Set<String> EXCLUDED = Set.of();

    private static SyncContentVerifier.Expected expected(String dn, Attribute... attrs) {
        return new SyncContentVerifier.Expected(dn, List.of(attrs), java.util.Set.of(), Map.of());
    }

    private static Map<String, SyncContentVerifier.Expected> expectedMap(String dn,
                                                                         SyncContentVerifier.Expected e) {
        Map<String, SyncContentVerifier.Expected> m = new LinkedHashMap<>();
        m.put(dn, e);
        return m;
    }

    @Test
    void matchingContent_isInSync() {
        var expected = expectedMap("uid=jo,ou=people,dc=t",
                expected("uid=jo,ou=people,dc=t", new Attribute("cn", "Jo"), new Attribute("sn", "Smith")));
        Map<String, Entry> actual = Map.of("uid=jo,ou=people,dc=t",
                new Entry("uid=jo,ou=people,dc=t", new Attribute("cn", "Jo"), new Attribute("sn", "Smith")));

        SyncVerifyResult r = SyncContentVerifier.compare(expected, actual, EXCLUDED, true, true);

        assertThat(r.inSync()).isEqualTo(1);
        assertThat(r.missingOnTarget()).isZero();
        assertThat(r.orphanOnTarget()).isZero();
        assertThat(r.contentMismatches()).isZero();
        assertThat(r.sourceMembers()).isEqualTo(1);
        assertThat(r.targetEntries()).isEqualTo(1);
    }

    @Test
    void heldReference_keptOnTheTarget_isNotDrift() {
        var expected = expectedMap("cn=eng,dc=t", new SyncContentVerifier.Expected("cn=eng,dc=t",
                List.of(new Attribute("cn", "eng")), Set.of("secdn"), Map.of()));
        Map<String, Entry> actual = Map.of("cn=eng,dc=t",
                new Entry("cn=eng,dc=t", new Attribute("cn", "eng"), new Attribute("secDN", "cn=eng,o=native")));

        SyncVerifyResult r = SyncContentVerifier.compare(expected, actual, EXCLUDED, true, true);

        assertThat(r.inSync()).isEqualTo(1);
        assertThat(r.contentMismatches()).isZero();
    }

    @Test
    void unresolvedMember_theTargetHasExactly_isNotDrift() {
        var expected = expectedMap("cn=g,dc=t", new SyncContentVerifier.Expected("cn=g,dc=t",
                List.of(new Attribute("member", "uid=a,dc=t")), Set.of(),
                Map.of("member", Map.of("secAuthority=default,cn=foo", "secAuthority=default,cn=foo"))));
        Map<String, Entry> actual = Map.of("cn=g,dc=t", new Entry("cn=g,dc=t",
                new Attribute("member", "uid=a, dc=t", "secAuthority=default, cn=foo")));

        SyncVerifyResult r = SyncContentVerifier.compare(expected, actual, EXCLUDED, true, true, null,
                List.of("member"));

        assertThat(r.inSync()).isEqualTo(1);
    }

    @Test
    void memberAbsentFromTarget_isMissing() {
        var expected = expectedMap("uid=jo,ou=people,dc=t",
                expected("uid=jo,ou=people,dc=t", new Attribute("cn", "Jo")));
        Map<String, Entry> actual = Map.of();

        SyncVerifyResult r = SyncContentVerifier.compare(expected, actual, EXCLUDED, true, true);

        assertThat(r.missingOnTarget()).isEqualTo(1);
        assertThat(r.sampleMissing()).containsExactly("uid=jo,ou=people,dc=t");
        assertThat(r.inSync()).isZero();
    }

    @Test
    void targetEntryWithNoSource_isOrphan() {
        Map<String, SyncContentVerifier.Expected> expected = Map.of();
        Map<String, Entry> actual = Map.of("uid=ex,ou=people,dc=t",
                new Entry("uid=ex,ou=people,dc=t", new Attribute("cn", "Ex")));

        SyncVerifyResult r = SyncContentVerifier.compare(expected, actual, EXCLUDED, true, true);

        assertThat(r.orphanOnTarget()).isEqualTo(1);
        assertThat(r.sampleOrphans()).containsExactly("uid=ex,ou=people,dc=t");
        assertThat(r.inSync()).isZero();
    }

    @Test
    void presentBothSides_differingAttributes_isMismatch() {
        var expected = expectedMap("uid=jo,ou=people,dc=t",
                expected("uid=jo,ou=people,dc=t", new Attribute("cn", "Jo"), new Attribute("mail", "jo@new")));
        Map<String, Entry> actual = Map.of("uid=jo,ou=people,dc=t",
                new Entry("uid=jo,ou=people,dc=t", new Attribute("cn", "Jo"), new Attribute("mail", "jo@old")));

        SyncVerifyResult r = SyncContentVerifier.compare(expected, actual, EXCLUDED, true, true);

        assertThat(r.contentMismatches()).isEqualTo(1);
        assertThat(r.sampleMismatches()).containsExactly("uid=jo,ou=people,dc=t");
        assertThat(r.inSync()).isZero();
    }

    @Test
    void excludedAttributes_doNotCountAsDrift() {
        // userPassword differs but is excluded — the entry is still in sync.
        var expected = expectedMap("uid=jo,ou=people,dc=t",
                expected("uid=jo,ou=people,dc=t", new Attribute("cn", "Jo")));
        Map<String, Entry> actual = Map.of("uid=jo,ou=people,dc=t",
                new Entry("uid=jo,ou=people,dc=t", new Attribute("cn", "Jo"),
                        new Attribute("userpassword", "secret")));

        SyncVerifyResult r = SyncContentVerifier.compare(expected, actual, Set.of("userpassword"), true, true);

        assertThat(r.inSync()).isEqualTo(1);
        assertThat(r.contentMismatches()).isZero();
    }

    @Test
    void dnKeysAreCallerNormalized_matchAcrossSpacingVariants() {
        // The verifier keys both maps by DN.normalizedString(); a match here proves
        // the comparison joins on those keys (not on display DN spelling).
        var expected = expectedMap("uid=jo,ou=people,dc=t",
                expected("uid=jo, ou=people, dc=t", new Attribute("cn", "Jo")));
        Map<String, Entry> actual = Map.of("uid=jo,ou=people,dc=t",
                new Entry("uid=jo,ou=people,dc=t", new Attribute("cn", "Jo")));

        SyncVerifyResult r = SyncContentVerifier.compare(expected, actual, EXCLUDED, true, true);

        assertThat(r.inSync()).isEqualTo(1);
        assertThat(r.orphanOnTarget()).isZero();
    }

    @Test
    void incompleteScans_propagateToResult() {
        SyncVerifyResult r = SyncContentVerifier.compare(Map.of(), Map.of(), EXCLUDED, false, true);
        assertThat(r.sourceComplete()).isFalse();
        assertThat(r.targetComplete()).isTrue();
    }

    @Test
    void aDnReferenceDifferingOnlyInForm_isInSync_notDrift() {
        var expected = expectedMap("cn=sec,dc=t",
                expected("cn=sec,dc=t", new Attribute("secDN", "cn=IVIA_Import_Test,o=Groups,c=us")));
        Map<String, Entry> actual = Map.of("cn=sec,dc=t",
                new Entry("cn=sec,dc=t", new Attribute("secDN", "cn=ivia_import_test, o=groups,c=us")));

        SyncVerifyResult r = SyncContentVerifier.compare(expected, actual, EXCLUDED, true, true, null,
                List.of("secDN"));

        assertThat(r.inSync()).isEqualTo(1);
        assertThat(r.contentMismatches()).isZero();
    }

    @Test
    void withAnAnchor_onlyAnchoredTargetEntriesAreOrphans() {
        Map<String, Entry> actual = new LinkedHashMap<>();
        actual.put("uid=gone,ou=people,dc=t", new Entry("uid=gone,ou=people,dc=t",
                new Attribute("cn", "Gone"), new Attribute("srcAnchor", "id-1")));
        actual.put("uid=other,ou=people,dc=t", new Entry("uid=other,ou=people,dc=t",
                new Attribute("cn", "Not ours")));

        SyncVerifyResult anchored = SyncContentVerifier.compare(Map.of(), actual, EXCLUDED, true, true,
                null, List.of(), SyncContentVerifier.CompareProgress.NONE, "srcAnchor",
                SyncContentVerifier.Owners.NONE);
        SyncVerifyResult unanchored = SyncContentVerifier.compare(Map.of(), actual, EXCLUDED, true, true);

        // Only the entry the set stamped is its orphan; the other isn't its to account for.
        assertThat(anchored.orphanOnTarget()).isEqualTo(1);
        assertThat(anchored.sampleOrphans()).containsExactly("uid=gone,ou=people,dc=t");
        assertThat(anchored.orphansAnchored()).isTrue();
        // Without an anchor every entry under the base is a candidate, as before.
        assertThat(unanchored.orphanOnTarget()).isEqualTo(2);
        assertThat(unanchored.orphansAnchored()).isFalse();
    }

    @org.junit.jupiter.api.Test
    void withAnAnchor_entriesAnotherSetPlacesThereAreNotOrphans() {
        // Both carry the shared anchor attribute; only "gone" is this set's leftover.
        Map<String, Entry> actual = new LinkedHashMap<>();
        actual.put("uid=gone,ou=people,dc=t", new Entry("uid=gone,ou=people,dc=t",
                new Attribute("cn", "Gone"), new Attribute("srcAnchor", "id-1")));
        actual.put("uid=theirs,ou=people,dc=t", new Entry("uid=theirs,ou=people,dc=t",
                new Attribute("cn", "Theirs"), new Attribute("srcAnchor", "id-2")));
        SyncContentVerifier.ManagedElsewhere otherSet = (value, dn) ->
                value.equals("id-2") && dn.equalsIgnoreCase("uid=theirs,ou=people,dc=t");

        SyncVerifyResult r = SyncContentVerifier.compare(Map.of(), actual, EXCLUDED, true, true,
                null, List.of(), SyncContentVerifier.CompareProgress.NONE, "srcAnchor",
                new SyncContentVerifier.Owners(otherSet, SyncContentVerifier.NOT_ELSEWHERE));

        assertThat(r.orphanOnTarget()).isEqualTo(1);
        assertThat(r.sampleOrphans()).containsExactly("uid=gone,ou=people,dc=t");
        assertThat(r.leavingSet()).isZero();
    }

    @Test
    void withAnAnchor_entriesThisSetStillTracksAreLeavingNotOrphans() {
        // "held" left the source scope but its delete is held for review (still in
        // this set's index); "gone" is in no index at all, a true orphan.
        Map<String, Entry> actual = new LinkedHashMap<>();
        actual.put("uid=gone,ou=people,dc=t", new Entry("uid=gone,ou=people,dc=t",
                new Attribute("cn", "Gone"), new Attribute("srcAnchor", "id-1")));
        actual.put("uid=held,ou=people,dc=t", new Entry("uid=held,ou=people,dc=t",
                new Attribute("cn", "Held"), new Attribute("srcAnchor", "id-3")));
        SyncContentVerifier.ManagedElsewhere thisSet = (value, dn) ->
                value.equals("id-3") && dn.equalsIgnoreCase("uid=held,ou=people,dc=t");

        SyncVerifyResult r = SyncContentVerifier.compare(Map.of(), actual, EXCLUDED, true, true,
                null, List.of(), SyncContentVerifier.CompareProgress.NONE, "srcAnchor",
                new SyncContentVerifier.Owners(SyncContentVerifier.NOT_ELSEWHERE, thisSet));

        assertThat(r.orphanOnTarget()).isEqualTo(1);
        assertThat(r.sampleOrphans()).containsExactly("uid=gone,ou=people,dc=t");
        assertThat(r.items()).extracting(SyncDriftItem::targetDn).containsExactly("uid=gone,ou=people,dc=t");
        assertThat(r.leavingSet()).isEqualTo(1);
        // Carried through when held references are attached afterwards.
        assertThat(r.withHeldReferences(null).leavingSet()).isEqualTo(1);
    }

    // ── held references ──────────────────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    void heldReferences_countsMembersAndTheOnesNamingMissingEntries() {
        var linked = MembershipDecision.in("1", "cn=a,dc=t", List.of(), new byte[0],
                Map.of("secDN", List.of("uid=ghost,ou=people,dc=s")), Map.of());
        var unsynced = MembershipDecision.in("2", "cn=b,dc=t", List.of(), new byte[0],
                Map.of(), Map.of("member", List.of("uid=pending,ou=people,dc=s")));
        var clean = MembershipDecision.in("3", "cn=c,dc=t", List.of(), new byte[0], Map.of(), Map.of());
        Map<String, MembershipDecision> members = new LinkedHashMap<>();
        members.put("cn=a,dc=t", linked);
        members.put("cn=b,dc=t", unsynced);
        members.put("cn=c,dc=t", clean);

        // ghost exists nowhere; pending exists on the source but isn't synced yet.
        var held = SyncContentVerifier.heldReferences(members,
                Map.of("cn=a,dc=t", "cn=a,ou=src", "cn=b,dc=t", "cn=b,ou=src"),
                dn -> !dn.startsWith("uid=ghost"));

        assertThat(held.entries()).isEqualTo(2);
        assertThat(held.dangling()).isEqualTo(1);
        assertThat(held.unchecked()).isFalse();
        assertThat(held.samples()).extracting(SyncVerifyResult.HeldSample::reference, SyncVerifyResult.HeldSample::dangling)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("uid=ghost,ou=people,dc=s", true),
                        org.assertj.core.groups.Tuple.tuple("uid=pending,ou=people,dc=s", false));
        // Every held value is kept for the Drift tab, with the source entry that holds it.
        assertThat(held.items()).extracting(com.ldapportal.dto.sync.SyncHeldReference::sourceDn,
                        com.ldapportal.dto.sync.SyncHeldReference::attribute,
                        com.ldapportal.dto.sync.SyncHeldReference::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("cn=a,ou=src", "secDN",
                                com.ldapportal.entity.enums.SyncHeldStatus.DANGLING),
                        org.assertj.core.groups.Tuple.tuple("cn=b,ou=src", "member",
                                com.ldapportal.entity.enums.SyncHeldStatus.UNSYNCED));
    }

    @org.junit.jupiter.api.Test
    void heldReferences_keepsEveryValueBeyondTheSamples_andLeavesUnreadOnesUnchecked() {
        List<String> refs = new java.util.ArrayList<>();
        for (int i = 0; i < SyncContentVerifier.SAMPLE_LIMIT + 5; i++) {
            refs.add("uid=u" + i + ",dc=s");
        }
        var group = MembershipDecision.in("g", "cn=g,dc=t", List.of(), new byte[0],
                Map.of("member", refs), Map.of());

        // A read that can't tell (null) is not evidence either way.
        var held = SyncContentVerifier.heldReferences(Map.of("cn=g,dc=t", group), Map.of(), dn -> null);

        assertThat(held.samples()).hasSize(SyncContentVerifier.SAMPLE_LIMIT);
        assertThat(held.items()).hasSize(refs.size())
                .allSatisfy(r -> assertThat(r.status()).isEqualTo(com.ldapportal.entity.enums.SyncHeldStatus.UNCHECKED));
        assertThat(held.unchecked()).isTrue();
        assertThat(held.dangling()).isZero();
    }

    // ── Drift items ─────────────────────────────────────────────────────────

    @Test
    void items_listEveryDisagreement_withTheSourceMember_andTheDifferingAttributes() {
        Map<String, SyncContentVerifier.Expected> expected = new LinkedHashMap<>();
        expected.put("uid=jo,dc=t", new SyncContentVerifier.Expected("uid=jo,dc=t",
                List.of(new Attribute("cn", "Jo"), new Attribute("title", "Lead"),
                        new Attribute("userPassword", "{SSHA}new")),
                Set.of(), Map.of(), "uid=jo,ou=staff,dc=s", "jo"));
        expected.put("uid=pn,dc=t", new SyncContentVerifier.Expected("uid=pn,dc=t",
                List.of(new Attribute("cn", "Pn")), Set.of(), Map.of(), "uid=pn,ou=staff,dc=s", "pn"));
        Map<String, Entry> actual = new LinkedHashMap<>();
        actual.put("uid=jo,dc=t", new Entry("uid=jo,dc=t", new Attribute("cn", "Jo"),
                new Attribute("title", "Eng"), new Attribute("userPassword", "{SSHA}old")));
        actual.put("uid=old,dc=t", new Entry("uid=old,dc=t", new Attribute("cn", "Old")));

        SyncVerifyResult r = SyncContentVerifier.compare(expected, actual, EXCLUDED, true, true);

        // Missing, then orphans, then content drift.
        assertThat(r.items()).extracting(i -> i.category().name() + " " + i.targetDn())
                .containsExactly("MISSING uid=pn,dc=t", "ORPHAN uid=old,dc=t", "MISMATCH uid=jo,dc=t");
        var missing = r.items().get(0);
        assertThat(missing.sourceDn()).isEqualTo("uid=pn,ou=staff,dc=s");
        assertThat(missing.identity()).isEqualTo("pn");
        assertThat(r.items().get(1).sourceDn()).isNull();
        var drift = r.items().get(2);
        assertThat(drift.attributes()).extracting(a -> a.attribute().toLowerCase())
                .containsExactlyInAnyOrder("title", "userpassword");
        var title = drift.attributes().stream().filter(a -> a.attribute().equalsIgnoreCase("title")).findFirst().get();
        assertThat(title.expected()).containsExactly("Lead");
        assertThat(title.actual()).containsExactly("Eng");
        // Password material: flagged as differing, values never kept.
        var pw = drift.attributes().stream().filter(a -> a.attribute().equalsIgnoreCase("userPassword")).findFirst().get();
        assertThat(pw.hidden()).isTrue();
        assertThat(pw.expected()).isEmpty();
        assertThat(pw.actual()).isEmpty();
    }

    @Test
    void attributeDiffs_capLongValueLists_andShowBinaryValuesBySize() {
        String[] many = new String[SyncContentVerifier.VALUE_LIMIT + 5];
        for (int i = 0; i < many.length; i++) many[i] = "uid=u" + i + ",dc=t";
        var desired = List.of(new Attribute("member", many), new Attribute("jpegPhoto", new byte[] {(byte) 0xFF, 0x00, 0x01}));
        Entry target = new Entry("cn=g,dc=t", new Attribute("member", "uid=x,dc=t"));
        var mods = List.of(
                new com.unboundid.ldap.sdk.Modification(com.unboundid.ldap.sdk.ModificationType.REPLACE, "member", many),
                new com.unboundid.ldap.sdk.Modification(com.unboundid.ldap.sdk.ModificationType.ADD, "jpegPhoto",
                        new byte[] {(byte) 0xFF, 0x00, 0x01}));

        var diffs = SyncContentVerifier.attributeDiffs(mods, desired, target);

        assertThat(diffs.get(0).expected()).hasSize(SyncContentVerifier.VALUE_LIMIT);
        assertThat(diffs.get(0).truncated()).isTrue();
        assertThat(diffs.get(0).actual()).containsExactly("uid=x,dc=t");
        assertThat(diffs.get(1).expected()).containsExactly("(binary, 3 bytes)");
        assertThat(diffs.get(1).actual()).isEmpty();
    }
}
