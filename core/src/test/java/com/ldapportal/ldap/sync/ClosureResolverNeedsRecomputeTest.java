// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Control;
import com.unboundid.ldap.sdk.SearchResultEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Which referrers a reconcile's deferred fan-out queues again. */
class ClosureResolverNeedsRecomputeTest {

    private static final String ALICE = "uid=alice,ou=people,dc=src";
    private static final String BOB = "uid=bob,ou=people,dc=src";
    private static final String GROUP = "cn=eng,ou=groups,dc=src";
    private static final List<String> REFS = List.of("member", "manager");

    /** alice changed at position 3 of the run. */
    private static final Map<String, Integer> CHANGED = Map.of(SyncDnUtil.normalize(ALICE), 3);

    private static SearchResultEntry entry(String dn, Attribute... attrs) {
        return new SearchResultEntry(dn, attrs, new Control[0]);
    }

    private static boolean needs(SearchResultEntry e, Integer visitedAt, boolean own) {
        return ClosureResolver.needsRecompute(e, SyncDnUtil.normalize(e.getDN()), REFS, CHANGED, visitedAt, own);
    }

    @Test
    void ownReferrer_recomputedBeforeTheChange_isQueued() {
        assertThat(needs(entry(GROUP, new Attribute("member", ALICE, BOB)), 1, true)).isTrue();
    }

    @Test
    void ownReferrer_recomputedAfterTheChange_isSkipped() {
        assertThat(needs(entry(GROUP, new Attribute("member", ALICE, BOB)), 5, true)).isFalse();
    }

    @Test
    void ownReferrer_theRunDidNotRecompute_isQueued() {
        assertThat(needs(entry(GROUP, new Attribute("member", ALICE)), null, true)).isTrue();
    }

    @Test
    void referrerInAnotherSet_isAlwaysQueued() {
        assertThat(needs(entry(GROUP, new Attribute("member", ALICE)), 5, false)).isTrue();
    }

    @Test
    void referenceMatchedInAnotherForm_counts() {
        assertThat(needs(entry(BOB, new Attribute("manager", "UID=Alice, OU=People, DC=src")), 5, true)).isFalse();
        assertThat(needs(entry(BOB, new Attribute("manager", "UID=Alice, OU=People, DC=src")), 1, true)).isTrue();
    }

    @Test
    void incompleteValues_areQueuedToBeSafe() {
        // AD returns a large member attribute in ranges; this range doesn't hold alice.
        assertThat(needs(entry(GROUP, new Attribute("member;range=0-1499", BOB)), 5, true)).isTrue();
    }

    @Test
    void rangedValues_areRead() {
        assertThat(needs(entry(GROUP, new Attribute("member;range=0-1499", ALICE, BOB)), 5, true)).isFalse();
    }

    @Test
    void selfReference_isNotQueued() {
        assertThat(needs(entry(ALICE, new Attribute("manager", ALICE)), 1, true)).isFalse();
    }
}
