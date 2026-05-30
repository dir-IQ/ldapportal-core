// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.directory;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.ldapportal.ldap.LdapGroupService;
import com.ldapportal.ldap.LdapUserService;
import com.ldapportal.ldap.model.LdapGroup;
import com.ldapportal.ldap.model.LdapUser;
import com.unboundid.ldap.sdk.LDAPConnection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * {@link DirectoryProvider} implementation that wraps the existing LDAP services.
 * Handles GENERIC, ACTIVE_DIRECTORY, OPENLDAP, IBM_DIRECTORY_SERVER, and
 * ORACLE_UNIFIED_DIRECTORY directory types. Both ITDS and OUD share the
 * generic LDAP code paths in this phase; vendor-specific optimisations
 * (ibm-allMembers / isMemberOf nested-group resolution, vendor
 * capability probes, changelog strategy selection) land in later phases
 * of the respective support plans.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LdapDirectoryProvider implements DirectoryProvider {

    private final LdapUserService userService;
    private final LdapGroupService groupService;
    private final LdapConnectionFactory connectionFactory;

    private static final String PERSON_FILTER =
            "(|(objectClass=inetOrgPerson)(&(objectClass=user)(!(objectClass=computer))))";

    private static final String GROUP_FILTER =
            "(|(objectClass=groupOfNames)(objectClass=groupOfUniqueNames)" +
            "(objectClass=posixGroup)(objectClass=group)(objectClass=groupOfURLs))";

    @Override
    public List<DirectoryType> supportedTypes() {
        return List.of(DirectoryType.GENERIC, DirectoryType.ACTIVE_DIRECTORY,
                DirectoryType.OPENLDAP, DirectoryType.IBM_DIRECTORY_SERVER,
                DirectoryType.ORACLE_UNIFIED_DIRECTORY);
    }

    // Attributes requested for every searchUsers / getUser call.
    //
    // The cross-directory identity resolver (ee.hybrid) reads
    // employeeNumber / employeeID / userPrincipalName off each Candidate
    // to drive secondary-key (EMPLOYEE_ID) and tertiary-key
    // (USER_PRINCIPAL_NAME) matching. Those attributes have to be
    // explicitly requested here — UnboundID's SearchRequest only returns
    // attributes the caller asked for, so omitting them silently
    // produced null values on the candidate side and made priority-2
    // and priority-3 matching dead on arrival for LDAP-backed
    // directories. Without this list, the only HIGH/AMBIGUOUS-tier
    // resolutions that could ever fire were ones where the candidates
    // ALSO matched on mail (priority 1) — which makes the secondary/
    // tertiary slots in the UI a no-op.
    private static final String[] USER_ATTRS = {
            "cn", "uid", "sAMAccountName", "mail", "displayName", "memberOf",
            "userAccountControl", "nsAccountLock", "ds-pwp-account-disabled",
            "employeeNumber", "employeeID", "userPrincipalName"
    };

    @Override
    public List<DirectoryUser> searchUsers(DirectoryConnection dc, String filter, int maxResults) {
        String ldapFilter = (filter != null && !filter.isBlank()) ? filter : PERSON_FILTER;
        List<LdapUser> users = userService.searchUsers(dc, ldapFilter, null, maxResults, USER_ATTRS);
        return users.stream().map(this::toDirectoryUser).toList();
    }

    @Override
    public DirectoryUser getUser(DirectoryConnection dc, String identifier) {
        LdapUser user = userService.getUser(dc, identifier, USER_ATTRS);
        return toDirectoryUser(user);
    }

    @Override
    public List<DirectoryGroup> searchGroups(DirectoryConnection dc, String filter, int maxResults) {
        String ldapFilter = (filter != null && !filter.isBlank()) ? filter : GROUP_FILTER;
        List<LdapGroup> groups = groupService.searchGroups(dc, ldapFilter, null, maxResults,
                "cn", "description", "member", "uniqueMember", "memberUid");
        return groups.stream().map(this::toDirectoryGroup).toList();
    }

    @Override
    public List<String> getGroupMembers(DirectoryConnection dc, String groupId) {
        // Detect member attribute by trying each
        LdapGroup group = groupService.getGroup(dc, groupId,
                "member", "uniqueMember", "memberUid");
        return group.getAllMembers();
    }

    @Override
    public List<DirectoryAuditEvent> pollAuditEvents(DirectoryConnection dc, OffsetDateTime since) {
        // LDAP audit events are handled by LdapChangelogReader, not this provider.
        // Return empty — the changelog reader writes directly to the audit_events table.
        return List.of();
    }

    @Override
    public String testConnection(DirectoryConnection dc) {
        try {
            LDAPConnection conn = connectionFactory.openUnboundConnection(dc);
            conn.close();
            return null; // success
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    private DirectoryUser toDirectoryUser(LdapUser u) {
        return new DirectoryUser(
                u.getDn(),
                u.getDisplayName() != null ? u.getDisplayName() : u.getCn(),
                u.getLoginName(),
                u.getMail(),
                isEnabled(u),
                u.getAttributes(),
                u.getMemberOf());
    }

    /**
     * Detect whether a user account is enabled.
     * <ul>
     *   <li>AD: userAccountControl bit 2 (0x2) = ACCOUNTDISABLE.</li>
     *   <li>OpenLDAP / 389 DS: nsAccountLock = "true" means locked/disabled.</li>
     *   <li>OUD / OpenDJ:      ds-pwp-account-disabled = "true" means disabled.</li>
     * </ul>
     *
     * <p>The chain is a "first 'true' wins" fold, which is the right
     * semantic for sticky-disabled: any attribute saying the account
     * is disabled means the account is disabled. The previous comment
     * claimed the three attributes are "non-overlapping" — that's only
     * true vendor-by-vendor in isolation, NOT in practice. OpenDJ in
     * particular can populate <em>both</em> nsAccountLock (via the
     * Sun / Netscape compatibility plugin) <em>and</em>
     * ds-pwp-account-disabled, and migration tooling commonly leaves
     * legacy nsAccountLock=true on entries that have been re-enabled
     * via the modern password-policy path. The current chain reports
     * such an entry as disabled — same as OpenDJ itself does when both
     * attributes are present, since OpenDJ's pwpolicy honours legacy
     * nsAccountLock as a disable signal. If a future deployment needs
     * pwpolicy-only semantics, switch to per-DirectoryType dispatch
     * here (and matching change in OperationalReportService's
     * runDisabledAccountsReport filter).
     */
    private boolean isEnabled(LdapUser u) {
        // Active Directory
        String uac = u.getUserAccountControl();
        if (uac != null) {
            try {
                return (Integer.parseInt(uac) & 0x2) == 0;
            } catch (NumberFormatException ignored) {}
        }
        // OpenLDAP / 389 DS
        String lock = u.getFirstValue("nsaccountlock");
        if ("true".equalsIgnoreCase(lock)) return false;
        // OUD / OpenDJ
        String pwp = u.getFirstValue("ds-pwp-account-disabled");
        if ("true".equalsIgnoreCase(pwp)) return false;
        // Default: assume enabled
        return true;
    }

    private DirectoryGroup toDirectoryGroup(LdapGroup g) {
        return new DirectoryGroup(
                g.getDn(),
                g.getCn(),
                g.getDescription(),
                g.getAllMembers().size());
    }
}
