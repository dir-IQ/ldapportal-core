// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.entity.Membership;
import com.ldapportal.entity.SyncSet;
import com.ldapportal.entity.enums.MembershipState;
import com.ldapportal.repository.MembershipRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Where an untranslated reference may be kept on the target. */
class UntranslatedReferenceTargetsTest {

    private final MembershipRepository rows = mock(MembershipRepository.class);
    private final SyncSet people = set("ou=people,o=src", "ou=Users,o=dst");

    private UntranslatedReferenceTargets.Lookup lookup() {
        return new UntranslatedReferenceTargets.Lookup(Set.of(people.getId()), List.of(people), rows);
    }

    private static SyncSet set(String sourceBase, String targetBase) {
        SyncSet s = new SyncSet();
        s.setId(UUID.randomUUID());
        s.setObjectScopeBaseDn(sourceBase);
        s.setTargetBaseDn(targetBase);
        return s;
    }

    private Membership row(SyncSet s, String sourceDn, String targetDn, MembershipState state) {
        Membership m = new Membership();
        m.setSyncSetId(s.getId());
        m.setSourceDn(SyncDnUtil.normalize(sourceDn));
        m.setTargetDn(targetDn);
        m.setState(state);
        return m;
    }

    @Test
    void aReferentTheSyncRecorded_isKeptAtItsRecordedTargetDn_whateverItsState() {
        when(rows.findAllBySourceDnIn(anyCollection())).thenReturn(List.of(
                row(people, "uid=x,ou=people,o=src", "uid=x,ou=Users,o=dst", MembershipState.FAILED)));

        assertThat(lookup().candidates(Map.of("member", List.of("uid=x,ou=people,o=src")), dn -> false))
                .containsExactly(Map.entry("member", Map.of("uid=x,ou=people,o=src", "uid=x,ou=Users,o=dst")));
    }

    @Test
    void anUnrecordedReferentInScope_isKeptAtItsPlacement_onlyWhileItExistsThere() {
        when(rows.findAllBySourceDnIn(anyCollection())).thenReturn(List.of());
        Map<String, List<String>> untranslated = Map.of("member", List.of("uid=y,ou=people,o=src"));

        assertThat(lookup().candidates(untranslated, dn -> dn.equals("uid=y,ou=Users,o=dst")))
                .containsExactly(Map.entry("member", Map.of("uid=y,ou=people,o=src", "uid=y,ou=Users,o=dst")));
        // The sync deleted it: nowhere to keep it.
        assertThat(lookup().candidates(untranslated, dn -> false)).isEmpty();
    }

    @Test
    void aReferentOutsideEverySetsScope_isKeptAsWritten() {
        when(rows.findAllBySourceDnIn(anyCollection())).thenReturn(List.of());

        assertThat(lookup().candidates(Map.of("member", List.of("secAuthority=default,cn=foo")), dn -> false))
                .containsExactly(Map.entry("member",
                        Map.of("secAuthority=default,cn=foo", "secAuthority=default,cn=foo")));
    }

    @Test
    void aReferentLeavingItsSet_hasNowhereToBeKept() {
        when(rows.findAllBySourceDnIn(anyCollection())).thenReturn(List.of(
                row(people, "uid=x,ou=people,o=src", "uid=x,ou=Users,o=dst", MembershipState.APPLIED)));

        assertThat(lookup().candidates(Map.of("member", List.of("uid=x,ou=people,o=src")), dn -> true,
                dn -> true)).isEmpty();
    }

    @Test
    void rowsOfSetsWritingElsewhere_areIgnored_andOthersMustAgree() {
        SyncSet elsewhere = set("ou=people,o=src", "ou=Other,o=dst");
        SyncSet second = set("ou=people,o=src", "ou=Second,o=dst");
        UntranslatedReferenceTargets.Lookup lookup = new UntranslatedReferenceTargets.Lookup(
                Set.of(UUID.randomUUID()), List.of(people, second), rows);
        when(rows.findAllBySourceDnIn(anyCollection())).thenReturn(List.of(
                row(elsewhere, "uid=x,ou=people,o=src", "uid=x,ou=Other,o=dst", MembershipState.APPLIED),
                row(people, "uid=x,ou=people,o=src", "uid=x,ou=Users,o=dst", MembershipState.APPLIED),
                row(second, "uid=x,ou=people,o=src", "uid=x,ou=Second,o=dst", MembershipState.APPLIED)));

        // Two sets on this target disagree: no recorded DN; in scope and not on the target → nothing.
        assertThat(lookup.candidates(Map.of("member", List.of("uid=x,ou=people,o=src")), dn -> false)).isEmpty();
    }

    @Test
    void manyReferents_takeOneRowQuery_andOneExistenceCheck() {
        when(rows.findAllBySourceDnIn(anyCollection())).thenReturn(List.of(
                row(people, "uid=x,ou=people,o=src", "uid=x,ou=Users,o=dst", MembershipState.APPLIED)));
        List<Set<String>> checks = new java.util.ArrayList<>();
        Map<String, List<String>> untranslated = new java.util.LinkedHashMap<>();
        untranslated.put("member", List.of("uid=x,ou=people,o=src", "uid=y,ou=people,o=src",
                "uid=z,ou=people,o=src", "cn=outside,o=elsewhere"));
        untranslated.put("owner", List.of("uid=y,ou=people,o=src"));

        Map<String, Map<String, String>> kept = lookup().candidatesInBulk(untranslated, dns -> {
            checks.add(Set.copyOf(dns));
            return Set.of("uid=y,ou=Users,o=dst");
        }, dn -> false);

        org.mockito.Mockito.verify(rows, org.mockito.Mockito.times(1)).findAllBySourceDnIn(anyCollection());
        // Only the unrecorded referents in scope need their placement checked, once.
        assertThat(checks).containsExactly(Set.of("uid=y,ou=Users,o=dst", "uid=z,ou=Users,o=dst"));
        assertThat(kept.get("member")).containsExactly(
                Map.entry("uid=x,ou=people,o=src", "uid=x,ou=Users,o=dst"),
                Map.entry("uid=y,ou=people,o=src", "uid=y,ou=Users,o=dst"),
                Map.entry("cn=outside,o=elsewhere", "cn=outside,o=elsewhere"));
        assertThat(kept.get("owner")).containsExactly(Map.entry("uid=y,ou=people,o=src", "uid=y,ou=Users,o=dst"));
    }
}
