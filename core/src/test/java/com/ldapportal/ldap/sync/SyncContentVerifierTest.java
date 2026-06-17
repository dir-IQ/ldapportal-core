// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

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
        return new SyncContentVerifier.Expected(dn, List.of(attrs));
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
}
