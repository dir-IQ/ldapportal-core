// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.core.provisioning.GroupMemberPlan;
import com.ldapportal.core.provisioning.PlanExecutor;
import com.ldapportal.core.provisioning.ProvisioningContext;
import com.ldapportal.core.provisioning.ProvisioningInterceptorChain;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.exception.LdapOperationException;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.ldap.model.LdapGroup;
import com.unboundid.asn1.ASN1OctetString;
import com.unboundid.ldap.sdk.*;
import com.unboundid.ldap.sdk.controls.SimplePagedResultsControl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LDAP group operations — search, read, create, delete, and member management.
 *
 * <p>Member attribute names differ by group schema:
 * <ul>
 *   <li>{@code member} — groupOfNames / AD groups (DN values)</li>
 *   <li>{@code uniqueMember} — groupOfUniqueNames (DN values)</li>
 *   <li>{@code memberUid} — posixGroup (UID strings)</li>
 * </ul>
 * The caller supplies the correct attribute name for their schema.
 * </p>
 */
@Service
@Slf4j
public class LdapGroupService {

    private final LdapConnectionFactory connectionFactory;
    private final ProvisioningInterceptorChain interceptors;
    private final PlanExecutor planExecutor;

    public LdapGroupService(LdapConnectionFactory connectionFactory,
                            ProvisioningInterceptorChain interceptors,
                            PlanExecutor planExecutor) {
        this.connectionFactory = connectionFactory;
        this.interceptors = interceptors;
        this.planExecutor = planExecutor;
    }

    // ── Search ────────────────────────────────────────────────────────────────

    /**
     * Searches for groups matching the given LDAP filter.
     *
     * @param dc         directory connection
     * @param filter     LDAP filter string
     * @param baseDn     search base (null falls back to connection's base DN)
     * @param attributes attributes to retrieve; empty = all
     */
    public List<LdapGroup> searchGroups(DirectoryConnection dc,
                                        String filter,
                                        String baseDn,
                                        String... attributes) {
        return searchGroups(dc, filter, baseDn, Integer.MAX_VALUE, attributes);
    }

    /**
     * Searches for groups with an upper bound on the number of results returned.
     *
     * <p>Pagination stops as soon as {@code maxResults} entries have been
     * collected, so the LDAP server is not asked to page beyond what the
     * caller actually needs.</p>
     *
     * @param dc         directory connection
     * @param filter     LDAP filter string
     * @param baseDn     search base (null falls back to connection's base DN)
     * @param maxResults maximum number of entries to return
     * @param attributes attributes to retrieve; empty = all
     */
    public List<LdapGroup> searchGroups(DirectoryConnection dc,
                                        String filter,
                                        String baseDn,
                                        int maxResults,
                                        String... attributes) {
        String searchBase = baseDn != null ? baseDn : dc.getBaseDn();
        int pageSize = Math.min(dc.getPagingSize(), maxResults);
        List<LdapGroup> results = new ArrayList<>();

        return connectionFactory.withConnection(dc, conn -> {
            ASN1OctetString cookie = null;
            do {
                SimplePagedResultsControl pagingRequest =
                    new SimplePagedResultsControl(pageSize, cookie);

                SearchRequest request = new SearchRequest(
                    searchBase, SearchScope.SUB,
                    Filter.create(filter), attributes);
                request.addControl(pagingRequest);

                SearchResult searchResult;
                try {
                    searchResult = conn.search(request);
                } catch (LDAPSearchException e) {
                    if (e.getResultCode() == ResultCode.NO_SUCH_OBJECT) {
                        log.debug("Search base '{}' does not exist — returning empty result", searchBase);
                        return results;
                    }
                    throw e;
                }

                for (SearchResultEntry entry : searchResult.getSearchEntries()) {
                    results.add(LdapEntryMapper.toGroup(entry));
                    if (results.size() >= maxResults) {
                        return results;
                    }
                }

                SimplePagedResultsControl pagingResponse =
                    SimplePagedResultsControl.get(searchResult);
                cookie = (pagingResponse != null && pagingResponse.moreResultsToReturn())
                    ? pagingResponse.getCookie()
                    : null;

            } while (cookie != null && cookie.getValue().length > 0);

            return results;
        });
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Retrieves a single group by distinguished name.
     *
     * @throws ResourceNotFoundException if no entry exists at {@code dn}
     */
    public LdapGroup getGroup(DirectoryConnection dc, String dn, String... attributes) {
        return connectionFactory.withConnection(dc, conn -> {
            SearchResultEntry entry = (attributes.length > 0)
                ? conn.getEntry(dn, attributes)
                : conn.getEntry(dn);

            if (entry == null) {
                throw new ResourceNotFoundException("LDAP group", dn);
            }
            return LdapEntryMapper.toGroup(entry);
        });
    }

    // ── Create ────────────────────────────────────────────────────────────────

    /**
     * Creates a new group entry at {@code dn}.
     *
     * @param dc         directory connection
     * @param dn         full DN for the new group
     * @param attributes attribute map for the new entry
     */
    public void createGroup(DirectoryConnection dc,
                            String dn,
                            Map<String, List<String>> attributes) {
        List<Attribute> ldapAttrs = new ArrayList<>();
        attributes.forEach((name, values) ->
            ldapAttrs.add(new Attribute(name, values.toArray(new String[0]))));

        connectionFactory.withConnection(dc, conn -> {
            LDAPResult result = conn.add(new AddRequest(dn, ldapAttrs));
            checkResult(result, "createGroup", dn);
            log.info("Created LDAP group {}", dn);
            return null;
        });
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    /**
     * Deletes the group entry at {@code dn}.
     */
    public void deleteGroup(DirectoryConnection dc, String dn) {
        connectionFactory.withConnection(dc, conn -> {
            LDAPResult result = conn.delete(dn);
            checkResult(result, "deleteGroup", dn);
            log.info("Deleted LDAP group {}", dn);
            return null;
        });
    }

    /**
     * Applies the given modifications to a group entry.
     */
    public void updateGroup(DirectoryConnection dc,
                            String dn,
                            List<Modification> modifications) {
        connectionFactory.withConnection(dc, conn -> {
            LDAPResult result = conn.modify(new ModifyRequest(dn, modifications));
            checkResult(result, "updateGroup", dn);
            log.info("Updated LDAP group {}", dn);
            return null;
        });
    }

    // ── Member management ─────────────────────────────────────────────────────

    /**
     * Adds {@code memberValue} to the group's {@code memberAttribute}.
     *
     * @param dc              directory connection
     * @param groupDn         group distinguished name
     * @param memberAttribute attribute to modify (e.g. {@code member}, {@code memberUid})
     * @param memberValue     DN or UID of the member to add
     */
    public void addMember(DirectoryConnection dc,
                          String groupDn,
                          String memberAttribute,
                          String memberValue) {
        addMember(dc, groupDn, memberAttribute, memberValue, null);
    }

    /**
     * Profile-aware overload. {@code memberProfileId} is the resolved
     * profile of the member being added, threaded into the
     * {@link ProvisioningContext} so an interceptor can exempt a
     * {@code FORCE_OFF} member from vendor-specific membership routing
     * (e.g. ISVA's secUser-DN rewrite).
     */
    public void addMember(DirectoryConnection dc,
                          String groupDn,
                          String memberAttribute,
                          String memberValue,
                          java.util.UUID memberProfileId) {
        // Routed through the interceptor chain so a vendor-aware
        // interceptor (P2+: ISVA refusing memberships when the
        // target group lacks objectClass: secGroup) can return a
        // refusal plan that surfaces as a 4xx rather than a
        // silently-ignored membership.
        GroupMemberPlan plan = interceptors.planGroupMembership(
                dc, groupDn, memberAttribute, memberValue,
                ProvisioningContext.of(memberProfileId));
        planExecutor.execute(dc, plan);
        log.info("Added member {} to group {}", memberValue, groupDn);
    }

    /**
     * Removes {@code memberValue} from the group's {@code memberAttribute}.
     */
    public void removeMember(DirectoryConnection dc,
                             String groupDn,
                             String memberAttribute,
                             String memberValue) {
        Modification mod = new Modification(ModificationType.DELETE, memberAttribute, memberValue);
        connectionFactory.withConnection(dc, conn -> {
            LDAPResult result = conn.modify(new ModifyRequest(groupDn, mod));
            checkResult(result, "removeMember", groupDn);
            log.info("Removed member {} from group {}", memberValue, groupDn);
            return null;
        });
    }

    /**
     * Returns all values of {@code memberAttribute} for the given group.
     */
    public List<String> getMembers(DirectoryConnection dc,
                                   String groupDn,
                                   String memberAttribute) {
        LdapGroup group = getGroup(dc, groupDn, memberAttribute);
        return group.getValues(memberAttribute);
    }

    /**
     * Returns all members of a group, including nested/transitive members.
     * Per-directory dispatch:
     * <ul>
     *   <li>Active Directory — server-side resolution via
     *       LDAP_MATCHING_RULE_IN_CHAIN (OID 1.2.840.113556.1.4.1941).</li>
     *   <li>Oracle Unified Directory — server-side resolution via the
     *       {@code isMemberOf} operational attribute. OUD (and its OpenDJ
     *       upstream) populate {@code isMemberOf} with the full transitive
     *       group closure for every user, so a single SUB-scoped filter
     *       returns the same answer the AD matching-rule path produces.</li>
     *   <li>Everything else — client-side recursive enumeration with
     *       cycle detection. One LDAP read per group discovered.</li>
     * </ul>
     */
    public List<String> getNestedMembers(DirectoryConnection dc, String groupDn) {
        return switch (dc.getDirectoryType()) {
            case ACTIVE_DIRECTORY         -> getNestedMembersAD(dc, groupDn);
            case ORACLE_UNIFIED_DIRECTORY -> getNestedMembersIsMemberOf(dc, groupDn);
            default                       -> getNestedMembersRecursive(dc, groupDn);
        };
    }

    private List<String> getNestedMembersAD(DirectoryConnection dc, String groupDn) {
        // AD's LDAP_MATCHING_RULE_IN_CHAIN resolves all transitive members server-side
        String filter = "(memberOf:1.2.840.113556.1.4.1941:=" + Filter.encodeValue(groupDn) + ")";
        return pagedDnSearch(dc, filter);
    }

    private List<String> getNestedMembersIsMemberOf(DirectoryConnection dc, String groupDn) {
        // OUD / OpenDJ expose `isMemberOf` as an operational attribute on
        // user entries, populated with the full transitive group closure.
        // A single SUB-scoped filter returns the same set the AD matching-
        // rule path produces, without the recursive walk the generic path
        // would do.
        //
        // Normalise the caller-supplied DN before encoding it into the
        // filter. OpenDJ's default attribute matching on operational attrs
        // is case- and whitespace-sensitive string equality — without
        // normalisation, 'CN=Engineering, OU=Groups, ...' from one caller
        // and 'cn=engineering,ou=groups,...' from the server's stored
        // isMemberOf value would silently fail to match (zero hits, no
        // error). DN.toNormalizedString() folds case, collapses spacing,
        // and standardises RDN attribute ordering — matching what the
        // server stores.
        //
        // If the input isn't a parseable DN, fall back to using it as-is
        // (with the trade-off above) rather than throwing — caller bugs
        // surface as zero hits rather than 500s, consistent with the AD
        // branch's behaviour on a malformed DN passed to the matching
        // rule.
        String normalized = groupDn;
        try {
            normalized = new com.unboundid.ldap.sdk.DN(groupDn).toNormalizedString();
        } catch (com.unboundid.ldap.sdk.LDAPException ex) {
            log.debug("isMemberOf groupDn not parseable, using as-is: {}", ex.getMessage());
        }
        String filter = "(isMemberOf=" + Filter.encodeValue(normalized) + ")";
        return pagedDnSearch(dc, filter);
    }

    /**
     * Server-side paged search returning entry DNs only. Shared by the AD
     * matching-rule branch and the OUD {@code isMemberOf} branch — both
     * produce the same shape (filter-only, base = directory base DN, no
     * attributes wanted), only the filter differs.
     */
    private List<String> pagedDnSearch(DirectoryConnection dc, String filter) {
        return connectionFactory.withConnection(dc, conn -> {
            List<String> members = new java.util.ArrayList<>();
            SearchRequest request = new SearchRequest(dc.getBaseDn(), SearchScope.SUB, filter, "1.1");
            ASN1OctetString cookie = null;
            do {
                request.setControls(new SimplePagedResultsControl(dc.getPagingSize(), cookie));
                SearchResult result = conn.search(request);
                for (SearchResultEntry entry : result.getSearchEntries()) {
                    members.add(entry.getDN());
                }
                SimplePagedResultsControl resp = SimplePagedResultsControl.get(result);
                cookie = (resp != null && resp.moreResultsToReturn()) ? resp.getCookie() : null;
            } while (cookie != null);
            return members;
        });
    }

    private List<String> getNestedMembersRecursive(DirectoryConnection dc, String groupDn) {
        java.util.Set<String> members = new java.util.LinkedHashSet<>();
        java.util.Set<String> visitedGroups = new java.util.HashSet<>();
        // Use a single connection for the entire recursive traversal
        connectionFactory.withConnection(dc, conn -> {
            resolveGroupRecursive(conn, groupDn, members, visitedGroups);
            return null;
        });
        return new java.util.ArrayList<>(members);
    }

    private void resolveGroupRecursive(LDAPConnection conn, String groupDn,
                                        java.util.Set<String> members, java.util.Set<String> visitedGroups) {
        if (!visitedGroups.add(groupDn.toLowerCase())) return;
        try {
            SearchResultEntry entry = conn.getEntry(groupDn, "member", "uniqueMember");
            if (entry == null) return;
            for (String attr : java.util.List.of("member", "uniqueMember")) {
                String[] vals = entry.getAttributeValues(attr);
                if (vals == null) continue;
                for (String memberDn : vals) {
                    if (memberDn.isBlank()) continue;
                    members.add(memberDn);
                    resolveGroupRecursive(conn, memberDn, members, visitedGroups);
                }
            }
        } catch (LDAPException e) {
            log.debug("Could not resolve nested members for {}: {}", groupDn, e.getMessage());
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void checkResult(LDAPResult result, String operation, String dn) {
        if (result.getResultCode() != ResultCode.SUCCESS) {
            throw new LdapOperationException(
                operation + " failed for [" + dn + "]: "
                + result.getResultCode() + " — " + result.getDiagnosticMessage());
        }
    }
}
