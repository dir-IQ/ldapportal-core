// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.core.provisioning.DeletePlan;
import com.ldapportal.core.provisioning.PasswordPlan;
import com.ldapportal.core.provisioning.PasswordSetPayload;
import com.ldapportal.core.provisioning.PlanExecutor;
import com.ldapportal.core.provisioning.ProvisioningContext;
import com.ldapportal.core.provisioning.ProvisioningInterceptorChain;
import com.ldapportal.core.provisioning.UserCreatePayload;
import com.ldapportal.core.provisioning.UserCreatePlan;
import com.ldapportal.core.provisioning.UserReadEnricherChain;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.EnableDisableValueType;
import com.ldapportal.exception.LdapOperationException;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.ldap.model.LdapUser;
import com.unboundid.asn1.ASN1OctetString;
import com.unboundid.ldap.sdk.*;
import com.unboundid.ldap.sdk.controls.SimplePagedResultsControl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * LDAP user operations — search, read, create, update, delete,
 * enable/disable, and move.
 *
 * <p>All operations borrow a connection from the {@link LdapConnectionFactory}
 * pool and return it when done.  Pagination is applied automatically on
 * searches using the Simple Paged Results control (RFC 2696).</p>
 */
@Service
@Slf4j
public class LdapUserService {

    private final LdapConnectionFactory connectionFactory;
    private final ProvisioningInterceptorChain interceptors;
    private final PlanExecutor planExecutor;
    private final UserReadEnricherChain readEnrichers;

    public LdapUserService(LdapConnectionFactory connectionFactory,
                           ProvisioningInterceptorChain interceptors,
                           PlanExecutor planExecutor,
                           UserReadEnricherChain readEnrichers) {
        this.connectionFactory = connectionFactory;
        this.interceptors = interceptors;
        this.planExecutor = planExecutor;
        this.readEnrichers = readEnrichers;
    }

    // ── Search ────────────────────────────────────────────────────────────────

    /**
     * Searches for users using the provided LDAP filter string.
     *
     * @param dc         directory connection to query
     * @param filter     LDAP filter, e.g. {@code (&(objectClass=inetOrgPerson)(cn=jo*))}
     * @param baseDn     search base (null falls back to the connection's base DN)
     * @param attributes attributes to retrieve; empty array retrieves all user attributes
     * @return matching users, paged using the connection's configured page size
     */
    public List<LdapUser> searchUsers(DirectoryConnection dc,
                                      String filter,
                                      String baseDn,
                                      String... attributes) {
        return searchUsers(dc, filter, baseDn, Integer.MAX_VALUE, attributes);
    }

    /**
     * Searches for users with an upper bound on the number of results returned.
     *
     * <p>Pagination stops as soon as {@code maxResults} entries have been
     * collected, so the LDAP server is not asked to page beyond what the
     * caller actually needs.</p>
     *
     * @param dc         directory connection to query
     * @param filter     LDAP filter
     * @param baseDn     search base (null falls back to the connection's base DN)
     * @param maxResults maximum number of entries to return
     * @param attributes attributes to retrieve; empty array retrieves all user attributes
     * @return up to {@code maxResults} matching users
     */
    public List<LdapUser> searchUsers(DirectoryConnection dc,
                                      String filter,
                                      String baseDn,
                                      int maxResults,
                                      String... attributes) {
        String searchBase = baseDn != null ? baseDn : dc.getBaseDn();
        // Clamp the page size: no point asking for more entries per page than the total limit
        int pageSize = Math.min(dc.getPagingSize(), maxResults);
        List<LdapUser> results = new ArrayList<>();

        return connectionFactory.withConnection(dc, conn -> {
            ASN1OctetString cookie = null;
            do {
                SimplePagedResultsControl pagingRequest =
                    new SimplePagedResultsControl(pageSize, cookie);

                SearchRequest request = new SearchRequest(
                    searchBase,
                    SearchScope.SUB,
                    Filter.create(filter),
                    attributes);
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

                // Collect this page first, then hand it to the enricher
                // chain as a batch. Per-page enrichment is the path that
                // lets a linked-mode joined-read issue ONE secUser search
                // per page instead of one per row. Early termination
                // (maxResults) is honoured before the enricher call so we
                // don't enrich rows we'll drop.
                List<LdapUser> page = new ArrayList<>();
                for (SearchResultEntry entry : searchResult.getSearchEntries()) {
                    page.add(LdapEntryMapper.toUser(entry));
                    if (results.size() + page.size() >= maxResults) {
                        break;
                    }
                }
                results.addAll(readEnrichers.enrichBatch(dc, page));
                if (results.size() >= maxResults) {
                    return results; // stop early — caller has everything
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

    /**
     * Streams all matching users to {@code consumer} page by page, without
     * accumulating the full result set in memory.  Use this for large exports
     * where loading every entry at once would be too expensive.
     *
     * @param dc         directory connection
     * @param filter     LDAP filter
     * @param baseDn     search base (null falls back to the connection's base DN)
     * @param consumer   called once per matching entry as results arrive
     * @param attributes attributes to retrieve; empty array retrieves all
     */
    public void processUsers(DirectoryConnection dc,
                             String filter,
                             String baseDn,
                             Consumer<LdapUser> consumer,
                             String... attributes) {
        String searchBase = baseDn != null ? baseDn : dc.getBaseDn();
        int pageSize = dc.getPagingSize();

        connectionFactory.withConnection(dc, conn -> {
            ASN1OctetString cookie = null;
            do {
                SimplePagedResultsControl pagingRequest =
                    new SimplePagedResultsControl(pageSize, cookie);
                SearchRequest request = new SearchRequest(
                    searchBase, SearchScope.SUB, Filter.create(filter), attributes);
                request.addControl(pagingRequest);

                SearchResult searchResult = conn.search(request);
                // Same per-page enrichment pattern as searchUsers above —
                // streaming callers see the same isva.* augmented shape.
                List<LdapUser> page = new ArrayList<>(searchResult.getEntryCount());
                for (SearchResultEntry entry : searchResult.getSearchEntries()) {
                    page.add(LdapEntryMapper.toUser(entry));
                }
                for (LdapUser enriched : readEnrichers.enrichBatch(dc, page)) {
                    consumer.accept(enriched);
                }

                SimplePagedResultsControl pagingResponse =
                    SimplePagedResultsControl.get(searchResult);
                cookie = (pagingResponse != null && pagingResponse.moreResultsToReturn())
                    ? pagingResponse.getCookie()
                    : null;
            } while (cookie != null && cookie.getValue().length > 0);
            return null;
        });
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Retrieves a single user by distinguished name.
     *
     * @throws ResourceNotFoundException if no entry exists at {@code dn}
     */
    public LdapUser getUser(DirectoryConnection dc, String dn, String... attributes) {
        LdapUser raw = connectionFactory.withConnection(dc, conn -> {
            SearchResultEntry entry = (attributes.length > 0)
                ? conn.getEntry(dn, attributes)
                : conn.getEntry(dn);

            if (entry == null) {
                throw new ResourceNotFoundException("LDAP user", dn);
            }
            return LdapEntryMapper.toUser(entry);
        });
        // Route through registered enrichers — adds isva.* attrs for
        // linked-mode ISVA directories, no-op for everything else.
        return readEnrichers.enrich(dc, raw);
    }

    // ── Create ────────────────────────────────────────────────────────────────

    /**
     * Creates a new user entry at {@code dn} with the given attributes.
     *
     * @param dc         directory connection
     * @param dn         full distinguished name for the new entry
     * @param attributes attribute map — keys are LDAP attribute names,
     *                   values are lists of string values
     */
    public void createUser(DirectoryConnection dc,
                           String dn,
                           Map<String, List<String>> attributes) {
        createUser(dc, dn, attributes, null);
    }

    /**
     * Profile-aware overload. {@code profileId} is threaded into the
     * {@link ProvisioningContext} so interceptors can make per-profile
     * decisions — e.g. ISVA exempting a {@code FORCE_OFF} profile, or
     * routing {@code secAuthority} per region.
     */
    public void createUser(DirectoryConnection dc,
                           String dn,
                           Map<String, List<String>> attributes,
                           java.util.UUID profileId) {
        UserCreatePlan plan = interceptors.planUserCreate(dc,
                UserCreatePayload.of(dn, attributes),
                ProvisioningContext.of(profileId));
        planExecutor.execute(dc, plan);
        log.info("Created LDAP user {}", dn);
    }

    // ── Update ────────────────────────────────────────────────────────────────

    /**
     * Applies the given modifications to an existing user entry.
     *
     * @param dc            directory connection
     * @param dn            distinguished name of the entry to modify
     * @param modifications list of LDAP modifications to apply
     */
    public void updateUser(DirectoryConnection dc,
                           String dn,
                           List<Modification> modifications) {
        connectionFactory.withConnection(dc, conn -> {
            LDAPResult result = conn.modify(new ModifyRequest(dn, modifications));
            checkResult(result, "updateUser", dn);
            log.info("Updated LDAP user {}", dn);
            return null;
        });
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    /**
     * Deletes the user entry at {@code dn}. With no interceptor
     * registered this is a single-step DEL (today's behaviour);
     * with an ISVA-aware interceptor (P2+) it may turn into a
     * MODIFY that sets {@code secAcctValid=FALSE} (soft-disable).
     */
    public void deleteUser(DirectoryConnection dc, String dn) {
        deleteUser(dc, dn, null);
    }

    /**
     * Profile-aware overload. {@code profileId} is threaded into the
     * {@link ProvisioningContext} so interceptors can make per-profile
     * decisions — e.g. ISVA fully exempting a {@code FORCE_OFF} profile
     * (plain LDAP delete, no secUser soft-disable).
     */
    public void deleteUser(DirectoryConnection dc, String dn, java.util.UUID profileId) {
        DeletePlan plan = interceptors.planUserDelete(dc, dn, ProvisioningContext.of(profileId));
        planExecutor.execute(dc, plan);
        log.info("Deleted LDAP user {}", dn);
    }

    // ── Enable / Disable ──────────────────────────────────────────────────────

    /**
     * Enables the user account by writing the configured enable value to the
     * directory connection's enable/disable attribute.
     *
     * @throws LdapOperationException if the connection has no enable/disable
     *                                attribute configured
     */
    public void enableUser(DirectoryConnection dc, String dn) {
        applyEnableDisable(dc, dn, true);
    }

    /**
     * Disables the user account.
     *
     * @throws LdapOperationException if the connection has no enable/disable
     *                                attribute configured
     */
    public void disableUser(DirectoryConnection dc, String dn) {
        applyEnableDisable(dc, dn, false);
    }

    private void applyEnableDisable(DirectoryConnection dc, String dn, boolean enable) {
        String attr = dc.getEnableDisableAttribute();
        if (attr == null || attr.isBlank()) {
            throw new LdapOperationException(
                "No enable/disable attribute configured for directory [" + dc.getDisplayName() + "]");
        }

        String value;
        if (dc.getEnableDisableValueType() == EnableDisableValueType.BOOLEAN) {
            value = enable ? "TRUE" : "FALSE";
        } else {
            value = enable ? dc.getEnableValue() : dc.getDisableValue();
        }

        Modification mod = new Modification(ModificationType.REPLACE, attr, value);
        updateUser(dc, dn, List.of(mod));
        log.info("{} LDAP user {}", enable ? "Enabled" : "Disabled", dn);
    }

    // ── Move ──────────────────────────────────────────────────────────────────

    /**
     * Moves a user to a new parent DN via the LDAP ModifyDN operation.
     *
     * @param dc          directory connection
     * @param dn          current distinguished name of the user
     * @param newParentDn target container DN (e.g. {@code ou=Staff,dc=example,dc=com})
     */
    public void moveUser(DirectoryConnection dc, String dn, String newParentDn) {
        // Extract the RDN from the current DN (everything before the first comma)
        String rdn = dn.contains(",") ? dn.substring(0, dn.indexOf(',')) : dn;

        connectionFactory.withConnection(dc, conn -> {
            LDAPResult result = conn.modifyDN(new ModifyDNRequest(dn, rdn, true, newParentDn));
            checkResult(result, "moveUser", dn);
            log.info("Moved LDAP user {} to {}", dn, newParentDn);
            return null;
        });
    }

    // ── Reset password ─────────────────────────────────────────────────────────

    /**
     * Resets the user's password. For Active Directory, uses the {@code unicodePwd}
     * attribute with UTF-16LE encoding over SSL. For other directories, replaces
     * the {@code userPassword} attribute.
     */
    public void resetPassword(DirectoryConnection dc, String dn, String newPassword) {
        resetPassword(dc, dn, newPassword, null);
    }

    /**
     * Profile-aware overload. {@code profileId} is threaded into the
     * {@link ProvisioningContext} so a {@code FORCE_OFF} profile's
     * password reset is plain LDAP (no secUser stamping).
     */
    public void resetPassword(DirectoryConnection dc, String dn, String newPassword,
                              java.util.UUID profileId) {
        // AD's UTF-16LE-encoded unicodePwd quirk (vs every other
        // directory's straight userPassword REPLACE) lives in
        // BaselinePlans.passwordSet — see the AD-encoding comment
        // there. Interceptors that want to additionally stamp
        // secPwdLastChanged (ISVA) produce multi-step plans on top.
        PasswordPlan plan = interceptors.planPasswordSet(dc, dn,
                new PasswordSetPayload(newPassword), ProvisioningContext.of(profileId));
        planExecutor.execute(dc, plan);
        log.info("Reset password for LDAP user {}", dn);
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
