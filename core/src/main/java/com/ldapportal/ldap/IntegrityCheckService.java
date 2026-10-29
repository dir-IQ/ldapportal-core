// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.dto.ldap.IntegrityReport;
import com.ldapportal.dto.ldap.IntegrityReport.IntegrityIssue;
import com.ldapportal.dto.ldap.IntegrityReport.IssueType;
import com.ldapportal.dto.ldap.RemoveBrokenReferencesRequest.Reference;
import com.ldapportal.dto.ldap.RemoveBrokenReferencesResult;
import com.ldapportal.dto.ldap.RemoveBrokenReferencesResult.Outcome;
import com.ldapportal.dto.ldap.RemoveBrokenReferencesResult.Status;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.exception.LdapOperationException;
import com.unboundid.asn1.ASN1OctetString;
import com.unboundid.ldap.sdk.*;
import com.unboundid.ldap.sdk.controls.SimplePagedResultsControl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Referential integrity checker for LDAP directories.
 * Searches for broken member references, orphaned entries, and empty groups,
 * and removes broken member references an operator picks from a report.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IntegrityCheckService {

    private final LdapConnectionFactory connectionFactory;
    /** Writes go through the directory browser's entry modify, an authorized write site. */
    private final LdapBrowseService browseService;

    private static final int PAGE_SIZE = 500;

    /** The attributes a broken member reference can be removed from. */
    static final List<String> MEMBER_ATTRIBUTES = List.of("member", "uniqueMember");

    /**
     * Runs the selected integrity checks against the directory.
     *
     * @param dc      directory connection
     * @param baseDn  base DN to search under (blank checks every root of the
     *                directory; a member under any root then counts as present)
     * @param checks  which checks to run (BROKEN_MEMBER, ORPHANED_ENTRY, EMPTY_GROUP)
     * @return report with all issues found
     */
    public IntegrityReport runChecks(DirectoryConnection dc, String baseDn,
                                     Set<IssueType> checks) {
        List<String> searchBases = (baseDn != null && !baseDn.isBlank()) ? List.of(baseDn) : dc.getBaseDns();

        return connectionFactory.withConnection(dc, conn -> {
            List<IntegrityIssue> issues = new ArrayList<>();

            // Pre-load all DNs once if needed by broken-member or orphaned
            // checks — across every searched root, so a group in one root
            // whose member lives in another isn't reported as broken.
            Set<String> allDns = null;
            if (checks.contains(IssueType.BROKEN_MEMBER) || checks.contains(IssueType.ORPHANED_ENTRY)) {
                allDns = new HashSet<>();
                for (String searchBase : searchBases) {
                    allDns.addAll(loadAllDns(conn, searchBase));
                }
                log.info("Loaded {} DNs from {}", allDns.size(), searchBases);
            }

            for (String searchBase : searchBases) {
                if (checks.contains(IssueType.BROKEN_MEMBER)) {
                    issues.addAll(checkBrokenMembers(conn, searchBase, allDns));
                }
                if (checks.contains(IssueType.EMPTY_GROUP)) {
                    issues.addAll(checkEmptyGroups(conn, searchBase));
                }
            }
            // Once over the combined set: each searched root is exempt (its
            // parent is outside the search by definition).
            if (checks.contains(IssueType.ORPHANED_ENTRY)) {
                issues.addAll(checkOrphanedEntries(allDns, searchBases));
            }

            log.info("Integrity check on {}: {} issues found", searchBases, issues.size());
            return new IntegrityReport(issues);
        });
    }

    /**
     * Loads all DNs under the base using paged search. Used as a lookup set
     * for both broken-member and orphaned-entry checks, avoiding N+1 queries.
     */
    private Set<String> loadAllDns(LDAPInterface conn, String baseDn) throws LDAPException {
        Set<String> dns = new HashSet<>();
        SearchRequest request = new SearchRequest(
                baseDn, SearchScope.SUB, "(objectClass=*)", "1.1");

        ASN1OctetString resumeCookie = null;
        do {
            request.setControls(new SimplePagedResultsControl(PAGE_SIZE, resumeCookie));
            SearchResult result;
            try {
                result = conn.search(request);
            } catch (LDAPSearchException e) {
                if (e.getResultCode() == ResultCode.NO_SUCH_OBJECT) return dns;
                // SIZE_LIMIT_EXCEEDED with partial results — use what we got
                if (e.getResultCode() == ResultCode.SIZE_LIMIT_EXCEEDED && e.getSearchEntries() != null) {
                    for (SearchResultEntry entry : e.getSearchEntries()) {
                        dns.add(normalizeDn(entry.getDN()));
                    }
                    log.warn("Size limit reached loading DNs from '{}', got {} entries", baseDn, dns.size());
                    return dns;
                }
                throw e;
            }

            for (SearchResultEntry entry : result.getSearchEntries()) {
                dns.add(normalizeDn(entry.getDN()));
            }

            SimplePagedResultsControl responseControl = SimplePagedResultsControl.get(result);
            resumeCookie = (responseControl != null && responseControl.moreResultsToReturn())
                    ? responseControl.getCookie() : null;
        } while (resumeCookie != null);

        return dns;
    }

    /**
     * Finds entries with member/uniqueMember attributes that reference DNs
     * which do not exist in the directory. Uses the pre-loaded DN set
     * instead of individual LDAP lookups per member.
     */
    private List<IntegrityIssue> checkBrokenMembers(LDAPInterface conn, String baseDn,
                                                     Set<String> allDns) throws LDAPException {
        List<IntegrityIssue> issues = new ArrayList<>();

        SearchRequest request = new SearchRequest(
                baseDn, SearchScope.SUB,
                "(|(member=*)(uniqueMember=*))",
                "member", "uniqueMember");

        ASN1OctetString resumeCookie = null;
        do {
            request.setControls(new SimplePagedResultsControl(PAGE_SIZE, resumeCookie));
            SearchResult result;
            try {
                result = conn.search(request);
            } catch (LDAPSearchException e) {
                if (e.getResultCode() == ResultCode.NO_SUCH_OBJECT) return issues;
                if (e.getResultCode() == ResultCode.SIZE_LIMIT_EXCEEDED && e.getSearchEntries() != null) {
                    for (SearchResultEntry entry : e.getSearchEntries()) {
                        checkMemberAttribute(entry, "member", allDns, issues);
                        checkMemberAttribute(entry, "uniqueMember", allDns, issues);
                    }
                    return issues;
                }
                throw e;
            }

            for (SearchResultEntry entry : result.getSearchEntries()) {
                checkMemberAttribute(entry, "member", allDns, issues);
                checkMemberAttribute(entry, "uniqueMember", allDns, issues);
            }

            SimplePagedResultsControl responseControl = SimplePagedResultsControl.get(result);
            resumeCookie = (responseControl != null && responseControl.moreResultsToReturn())
                    ? responseControl.getCookie() : null;
        } while (resumeCookie != null);

        return issues;
    }

    private void checkMemberAttribute(SearchResultEntry entry, String attrName,
                                       Set<String> allDns, List<IntegrityIssue> issues) {
        String[] values = entry.getAttributeValues(attrName);
        if (values == null) return;

        for (String memberDn : values) {
            if (memberDn.isBlank()) continue;
            // Compared as DNs: a value differing from the entry's DN only in
            // case or spacing isn't broken.
            if (!allDns.contains(normalizeDn(memberDn))) {
                issues.add(new IntegrityIssue(
                        IssueType.BROKEN_MEMBER,
                        entry.getDN(),
                        "Attribute '" + attrName + "' references non-existent DN: " + memberDn,
                        attrName, memberDn));
            }
        }
    }

    /**
     * Finds entries whose parent DN does not exist in the directory.
     * Uses the pre-loaded DN set — no additional LDAP queries needed.
     */
    private List<IntegrityIssue> checkOrphanedEntries(Set<String> allDns, List<String> searchBases) {
        List<IntegrityIssue> issues = new ArrayList<>();
        Set<String> roots = new HashSet<>();
        for (String base : searchBases) {
            roots.add(normalizeDn(base));
        }

        for (String dn : allDns) {
            if (roots.contains(dn)) continue;

            String parentDn = extractParentDn(dn);
            if (parentDn != null && !allDns.contains(parentDn)) {
                issues.add(new IntegrityIssue(
                        IssueType.ORPHANED_ENTRY,
                        dn,
                        "Parent DN does not exist: " + parentDn));
            }
        }

        return issues;
    }

    /**
     * Finds group entries that have no members. Uses paged search.
     */
    private List<IntegrityIssue> checkEmptyGroups(LDAPInterface conn,
                                                   String baseDn) throws LDAPException {
        List<IntegrityIssue> issues = new ArrayList<>();

        SearchRequest request = new SearchRequest(
                baseDn, SearchScope.SUB,
                "(|(objectClass=groupOfNames)(objectClass=groupOfUniqueNames)(objectClass=posixGroup))",
                "member", "uniqueMember", "memberUid");

        ASN1OctetString resumeCookie = null;
        do {
            request.setControls(new SimplePagedResultsControl(PAGE_SIZE, resumeCookie));
            SearchResult result;
            try {
                result = conn.search(request);
            } catch (LDAPSearchException e) {
                if (e.getResultCode() == ResultCode.NO_SUCH_OBJECT) return issues;
                if (e.getResultCode() == ResultCode.SIZE_LIMIT_EXCEEDED && e.getSearchEntries() != null) {
                    for (SearchResultEntry entry : e.getSearchEntries()) {
                        checkEmptyGroup(entry, issues);
                    }
                    return issues;
                }
                throw e;
            }

            for (SearchResultEntry entry : result.getSearchEntries()) {
                checkEmptyGroup(entry, issues);
            }

            SimplePagedResultsControl responseControl = SimplePagedResultsControl.get(result);
            resumeCookie = (responseControl != null && responseControl.moreResultsToReturn())
                    ? responseControl.getCookie() : null;
        } while (resumeCookie != null);

        return issues;
    }

    private void checkEmptyGroup(SearchResultEntry entry, List<IntegrityIssue> issues) {
        for (String attr : List.of("member", "uniqueMember", "memberUid")) {
            String[] values = entry.getAttributeValues(attr);
            if (values != null && values.length > 0) return;
        }
        issues.add(new IntegrityIssue(
                IssueType.EMPTY_GROUP,
                entry.getDN(),
                "Group has no members"));
    }

    // ── Remove broken references ────────────────────────────────────────────

    /**
     * Removes broken member references from their groups. A report can be
     * stale, so each reference is checked again first: one whose DN now names
     * an entry is kept ({@code KEPT_ENTRY_EXISTS}), and one the group no
     * longer holds is skipped ({@code ALREADY_GONE}). Each group's remaining
     * references are removed in a single modify, so a group either loses all
     * of them or, when the server refuses the modify (e.g. a
     * {@code groupOfNames} would be left with no {@code member}), none
     * ({@code FAILED}, with the server's reason).
     *
     * @throws IllegalArgumentException for an attribute other than {@code member} / {@code uniqueMember}
     */
    public RemoveBrokenReferencesResult removeBrokenReferences(DirectoryConnection dc, List<Reference> refs) {
        for (Reference ref : refs) {
            if (memberAttribute(ref.attribute()) == null) {
                throw new IllegalArgumentException("Only member and uniqueMember references can be removed, not '"
                        + ref.attribute() + "'.");
            }
        }
        Outcome[] outcomes = new Outcome[refs.size()];
        // Read phase: re-check each reference and plan one modify per group.
        List<GroupRemoval> removals = connectionFactory.withConnection(dc, conn -> {
            // Per group (as a DN), per attribute: the request positions to remove.
            Map<String, Map<String, List<Integer>>> byGroup = new LinkedHashMap<>();
            for (int i = 0; i < refs.size(); i++) {
                Reference ref = refs.get(i);
                if (entryExists(conn, ref.value())) {
                    outcomes[i] = outcome(ref, Status.KEPT_ENTRY_EXISTS, "An entry exists at this DN now.");
                    continue;
                }
                byGroup.computeIfAbsent(normalizeDn(ref.groupDn()), k -> new LinkedHashMap<>())
                        .computeIfAbsent(memberAttribute(ref.attribute()), k -> new ArrayList<>()).add(i);
            }
            List<GroupRemoval> planned = new ArrayList<>();
            for (Map<String, List<Integer>> byAttr : byGroup.values()) {
                String groupDn = refs.get(byAttr.values().iterator().next().get(0)).groupDn();
                Entry group = readGroup(conn, groupDn);
                List<Modification> mods = new ArrayList<>();
                List<Integer> removing = new ArrayList<>();
                for (Map.Entry<String, List<Integer>> attr : byAttr.entrySet()) {
                    List<String> stored = new ArrayList<>();
                    for (int i : attr.getValue()) {
                        String value = group == null ? null : storedValue(group, attr.getKey(), refs.get(i).value());
                        if (value == null) {
                            outcomes[i] = outcome(refs.get(i), Status.ALREADY_GONE,
                                    group == null ? "The group no longer exists." : "The group no longer holds it.");
                        } else {
                            if (!stored.contains(value)) {
                                stored.add(value);
                            }
                            removing.add(i);
                        }
                    }
                    if (!stored.isEmpty()) {
                        mods.add(new Modification(ModificationType.DELETE, attr.getKey(),
                                stored.toArray(new String[0])));
                    }
                }
                if (!mods.isEmpty()) {
                    planned.add(new GroupRemoval(groupDn, mods, removing));
                }
            }
            return planned;
        });

        // Write phase: one modify per group, all or nothing for that group.
        int removed = 0;
        for (GroupRemoval g : removals) {
            String failure = null;
            try {
                browseService.updateEntry(dc, g.groupDn(), g.mods());
            } catch (LdapOperationException e) {
                failure = e.getCause() instanceof LDAPException le
                        ? describe(le.getResultCode(), le.getDiagnosticMessage())
                        : e.getMessage();
            }
            for (int i : g.removing()) {
                outcomes[i] = failure == null
                        ? outcome(refs.get(i), Status.REMOVED, null)
                        : outcome(refs.get(i), Status.FAILED, failure);
            }
            if (failure == null) {
                removed += g.removing().size();
                log.info("Removed {} broken reference(s) from {}", g.removing().size(), g.groupDn());
            } else {
                log.warn("Could not remove broken references from {}: {}", g.groupDn(), failure);
            }
        }
        return new RemoveBrokenReferencesResult(removed, List.of(outcomes));
    }

    /** One group's planned removal: its modifications, and the request positions they remove. */
    private record GroupRemoval(String groupDn, List<Modification> mods, List<Integer> removing) {
    }

    /** The canonical name of a member attribute, or {@code null} for any other attribute. */
    private static String memberAttribute(String attr) {
        for (String a : MEMBER_ATTRIBUTES) {
            if (a.equalsIgnoreCase(attr)) {
                return a;
            }
        }
        return null;
    }

    /** Whether an entry exists at {@code dn}; a value that isn't a valid DN names none. */
    private static boolean entryExists(LDAPInterface conn, String dn) throws LDAPException {
        try {
            return conn.getEntry(dn, "1.1") != null;
        } catch (LDAPException e) {
            if (e.getResultCode() == ResultCode.NO_SUCH_OBJECT || e.getResultCode() == ResultCode.INVALID_DN_SYNTAX) {
                return false;
            }
            throw e;
        }
    }

    private static Entry readGroup(LDAPInterface conn, String dn) throws LDAPException {
        try {
            return conn.getEntry(dn, MEMBER_ATTRIBUTES.toArray(new String[0]));
        } catch (LDAPException e) {
            if (e.getResultCode() == ResultCode.NO_SUCH_OBJECT) {
                return null;
            }
            throw e;
        }
    }

    /** The group's stored value of {@code attr} that is the same DN as {@code value}, or {@code null}. */
    private static String storedValue(Entry group, String attr, String value) {
        String[] values = group.getAttributeValues(attr);
        if (values == null) {
            return null;
        }
        String wanted = normalizeDn(value);
        for (String v : values) {
            if (v.equals(value) || normalizeDn(v).equals(wanted)) {
                return v;
            }
        }
        return null;
    }

    private static Outcome outcome(Reference ref, Status status, String message) {
        return new Outcome(ref.groupDn(), ref.attribute(), ref.value(), status, message);
    }

    private static String describe(ResultCode code, String diagnostic) {
        String reason = code == ResultCode.OBJECT_CLASS_VIOLATION
                ? "The server refused: the group would be left without a required member attribute"
                : "The server refused: " + code;
        return diagnostic == null || diagnostic.isBlank() ? reason : reason + " (" + diagnostic.trim() + ")";
    }

    /** A DN in normalized form (case and spacing folded), for comparing DNs; a string that isn't one, lowercased. */
    static String normalizeDn(String dn) {
        try {
            return new DN(dn).toNormalizedString();
        } catch (LDAPException e) {
            return dn.toLowerCase(Locale.ROOT);
        }
    }

    private String extractParentDn(String dn) {
        int idx = dn.indexOf(',');
        if (idx < 0 || idx + 1 >= dn.length()) {
            return null;
        }
        return dn.substring(idx + 1);
    }
}
