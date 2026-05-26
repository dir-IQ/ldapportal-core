// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.dto.ldap.IntegrityReport;
import com.ldapportal.dto.ldap.IntegrityReport.IntegrityIssue;
import com.ldapportal.dto.ldap.IntegrityReport.IssueType;
import com.ldapportal.entity.DirectoryConnection;
import com.unboundid.asn1.ASN1OctetString;
import com.unboundid.ldap.sdk.*;
import com.unboundid.ldap.sdk.controls.SimplePagedResultsControl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Referential integrity checker for LDAP directories.
 * Searches for broken member references, orphaned entries, and empty groups.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IntegrityCheckService {

    private final LdapConnectionFactory connectionFactory;

    private static final int PAGE_SIZE = 500;

    /**
     * Runs the selected integrity checks against the directory.
     *
     * @param dc      directory connection
     * @param baseDn  base DN to search under
     * @param checks  which checks to run (BROKEN_MEMBER, ORPHANED_ENTRY, EMPTY_GROUP)
     * @return report with all issues found
     */
    public IntegrityReport runChecks(DirectoryConnection dc, String baseDn,
                                     Set<IssueType> checks) {
        String searchBase = (baseDn != null && !baseDn.isBlank()) ? baseDn : dc.getBaseDn();

        return connectionFactory.withConnection(dc, conn -> {
            List<IntegrityIssue> issues = new ArrayList<>();

            // Pre-load all DNs once if needed by broken-member or orphaned checks
            Set<String> allDns = null;
            if (checks.contains(IssueType.BROKEN_MEMBER) || checks.contains(IssueType.ORPHANED_ENTRY)) {
                allDns = loadAllDns(conn, searchBase);
                log.info("Loaded {} DNs from '{}'", allDns.size(), searchBase);
            }

            if (checks.contains(IssueType.BROKEN_MEMBER)) {
                issues.addAll(checkBrokenMembers(conn, searchBase, allDns));
            }
            if (checks.contains(IssueType.ORPHANED_ENTRY)) {
                issues.addAll(checkOrphanedEntries(allDns, searchBase));
            }
            if (checks.contains(IssueType.EMPTY_GROUP)) {
                issues.addAll(checkEmptyGroups(conn, searchBase));
            }

            log.info("Integrity check on '{}': {} issues found", searchBase, issues.size());
            return new IntegrityReport(issues);
        });
    }

    /**
     * Loads all DNs under the base using paged search. Used as a lookup set
     * for both broken-member and orphaned-entry checks, avoiding N+1 queries.
     */
    private Set<String> loadAllDns(LDAPConnection conn, String baseDn) throws LDAPException {
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
                        dns.add(entry.getDN().toLowerCase());
                    }
                    log.warn("Size limit reached loading DNs from '{}', got {} entries", baseDn, dns.size());
                    return dns;
                }
                throw e;
            }

            for (SearchResultEntry entry : result.getSearchEntries()) {
                dns.add(entry.getDN().toLowerCase());
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
    private List<IntegrityIssue> checkBrokenMembers(LDAPConnection conn, String baseDn,
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
            if (!allDns.contains(memberDn.toLowerCase())) {
                issues.add(new IntegrityIssue(
                        IssueType.BROKEN_MEMBER,
                        entry.getDN(),
                        "Attribute '" + attrName + "' references non-existent DN: " + memberDn));
            }
        }
    }

    /**
     * Finds entries whose parent DN does not exist in the directory.
     * Uses the pre-loaded DN set — no additional LDAP queries needed.
     */
    private List<IntegrityIssue> checkOrphanedEntries(Set<String> allDns, String baseDn) {
        List<IntegrityIssue> issues = new ArrayList<>();

        for (String dn : allDns) {
            if (dn.equalsIgnoreCase(baseDn)) continue;

            String parentDn = extractParentDn(dn);
            if (parentDn != null && !allDns.contains(parentDn.toLowerCase())) {
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
    private List<IntegrityIssue> checkEmptyGroups(LDAPConnection conn,
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

    private String extractParentDn(String dn) {
        int idx = dn.indexOf(',');
        if (idx < 0 || idx + 1 >= dn.length()) {
            return null;
        }
        return dn.substring(idx + 1);
    }
}
