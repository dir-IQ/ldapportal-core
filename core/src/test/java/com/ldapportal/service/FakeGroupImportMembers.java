// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.dto.csv.GroupMemberChange.Outcome;
import com.ldapportal.dto.ldap.MembershipChangeRequest;
import com.ldapportal.dto.ldap.MembershipChangeRequest.Op;
import com.ldapportal.dto.ldap.MembershipChangeResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * In-memory {@link GroupImportMembers} for {@link BulkGroupService} tests:
 * a fixed set of users and groups, and a log of the governed writes.
 */
final class FakeGroupImportMembers implements GroupImportMembers {

    /** One call to {@link #apply}. */
    record Write(String memberDn, String memberUid, String groupDn, String memberAttribute, Op op) {}

    private final Map<String, Member> members = new HashMap<>();
    private final Map<String, ExistingGroup> groups = new HashMap<>();
    final List<Write> writes = new ArrayList<>();
    /** Set to refuse member changes, as a caller without group.manage_members would be. */
    boolean mayManageMembers = true;
    int memberChangeChecks;
    /** Decides each governed write's outcome; applied by default. */
    Function<Write, MembershipChangeResult.Status> outcome = w -> MembershipChangeResult.Status.APPLIED;

    /** A user that resolves both by uid and by DN. */
    FakeGroupImportMembers user(String uid, String dn) {
        Member m = Member.of(dn, uid);
        members.put(uid.toLowerCase(Locale.ROOT), m);
        members.put(dn.toLowerCase(Locale.ROOT), m);
        return this;
    }

    /** An entry with no uid (e.g. a nested group), resolvable by DN only. */
    FakeGroupImportMembers entry(String dn) {
        members.put(dn.toLowerCase(Locale.ROOT), Member.of(dn, null));
        return this;
    }

    /** A value that resolves to a problem (not found, ambiguous, out of scope…). */
    FakeGroupImportMembers problem(String value, Outcome problem) {
        members.put(value.toLowerCase(Locale.ROOT), Member.problem(problem, problem.name()));
        return this;
    }

    /** An existing group using {@code memberAttribute}, with the given member DNs / uids. */
    FakeGroupImportMembers group(String dn, String memberAttribute, String... memberValues) {
        Set<String> dns = new HashSet<>();
        Set<String> uids = new HashSet<>();
        for (String v : memberValues) {
            if (v.contains("=")) dns.add(GroupImportMembers.normalizeDn(v));
            else uids.add(v.toLowerCase(Locale.ROOT));
        }
        groups.put(GroupImportMembers.normalizeDn(dn),
                new ExistingGroup(dn, true, memberAttribute, dns, uids));
        return this;
    }

    /** An existing entry at {@code dn} that isn't a group. */
    FakeGroupImportMembers nonGroup(String dn) {
        groups.put(GroupImportMembers.normalizeDn(dn),
                new ExistingGroup(dn, false, "member", Set.of(), Set.of()));
        return this;
    }

    @Override
    public void requireMemberChanges() {
        memberChangeChecks++;
        if (!mayManageMembers) {
            throw new org.springframework.security.access.AccessDeniedException("group.manage_members required");
        }
    }

    @Override
    public Member resolveMember(String value) {
        Member m = members.get(value.toLowerCase(Locale.ROOT));
        return m != null ? m : Member.problem(Outcome.MEMBER_NOT_FOUND, "No user with uid=" + value);
    }

    @Override
    public ExistingGroup findGroup(String dn) {
        return groups.get(GroupImportMembers.normalizeDn(dn));
    }

    @Override
    public MembershipChangeResult.Item apply(Member member, String groupDn, String memberAttribute, Op op) {
        Write w = new Write(member.dn(), member.uid(), groupDn, memberAttribute, op);
        writes.add(w);
        MembershipChangeRequest.Change c = new MembershipChangeRequest.Change(groupDn, memberAttribute, op);
        return switch (outcome.apply(w)) {
            case APPLIED -> MembershipChangeResult.Item.applied(c);
            case QUEUED_FOR_APPROVAL -> MembershipChangeResult.Item.queued(c, APPROVAL_ID);
            case REFUSED -> MembershipChangeResult.Item.refused(c, "refused by interceptor");
            case BLOCKED -> MembershipChangeResult.Item.blocked(c, "SoD violation");
            case ERROR -> MembershipChangeResult.Item.errored(c, "LDAP said no");
        };
    }

    static final UUID APPROVAL_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
}
