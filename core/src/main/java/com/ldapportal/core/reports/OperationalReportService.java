// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.reports;

import com.ldapportal.dto.profile.GroupChangePreview;
import com.ldapportal.entity.AuditEvent;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.ProvisioningProfile;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.ldap.LdapGroupService;
import com.ldapportal.ldap.LdapUserService;
import com.ldapportal.ldap.model.LdapGroup;
import com.ldapportal.ldap.model.LdapUser;
import com.ldapportal.repository.AuditEventRepository;
import com.ldapportal.repository.ProvisioningProfileRepository;
import com.ldapportal.service.ProvisioningProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Executes operational reports on demand. Operational reports are
 * directory metrics + integrity reports that every operator needs to
 * manage the directory itself — they aren't compliance-specific, so
 * they live in core and are not entitlement-gated.
 *
 * <p>Compliance-flavoured reports (access reviews, SoD, drift,
 * termination velocity, audit-log exports, privileged-account
 * inventory) and scheduled execution + signed PDF output live in
 * {@code ee/governance}.</p>
 *
 * <h3>Report types</h3>
 * <ul>
 *   <li><b>USERS_IN_GROUP</b>      — members of a specific group; param {@code groupDn}.</li>
 *   <li><b>USERS_IN_BRANCH</b>     — users under a base DN; param {@code branchDn}.</li>
 *   <li><b>USERS_WITH_NO_GROUP</b> — users not present in any group's member list.</li>
 *   <li><b>RECENTLY_ADDED</b>      — entries with createTimestamp ≥ now − lookbackDays.</li>
 *   <li><b>RECENTLY_MODIFIED</b>   — entries with modifyTimestamp ≥ now − lookbackDays.</li>
 *   <li><b>RECENTLY_DELETED</b>    — audit events for USER_DELETE / GROUP_DELETE plus
 *                                    cn=changelog delete events.</li>
 *   <li><b>DISABLED_ACCOUNTS</b>   — disabled by AD UAC bit or directory-specific flags.</li>
 *   <li><b>MISSING_PROFILE_GROUPS</b> — gap analysis from provisioning profile evaluation.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OperationalReportService {

    private static final DateTimeFormatter LDAP_TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'");

    /** Maximum LDAP entries returned per report to prevent OOM. */
    private static final int MAX_LDAP_RESULTS = 10_000;

    /** Maximum audit rows returned by the audit-entries report. */
    private static final int MAX_AUDIT_RESULTS = 5_000;

    private final LdapUserService                userService;
    private final LdapGroupService               groupService;
    private final AuditEventRepository           auditEventRepo;
    private final ProvisioningProfileRepository  profileRepo;
    private final ProvisioningProfileService     profileService;
    /**
     * Addon-contributed reports. Empty in community (no addon on the
     * classpath); populated by e.g. the IVIA addon's orphaned-account report.
     * Consulted only after the built-in {@link OperationalReportType} names
     * fail to match the requested type.
     */
    private final List<OperationalReportProvider> reportProviders;

    /**
     * Runs the requested operational report and returns the structured
     * data (columns + rows) for inline display or downstream rendering.
     *
     * <p>{@code reportType} names either a built-in {@link OperationalReportType}
     * or an addon-contributed {@link OperationalReportProvider#reportId()};
     * anything matching neither is a 400 ({@link IllegalArgumentException}).</p>
     */
    public ReportData run(DirectoryConnection dc,
                          String reportType,
                          Map<String, Object> params,
                          UUID directoryId) {
        requireLdapDirectory(dc);
        Map<String, Object> safeParams = params != null ? params : Map.of();
        // Admin-view scoping: when reportParams carries scopeBaseDn
        // (set by the frontend for non-superadmin sessions from the
        // picked profile's targetUserDn), use it as the LDAP search
        // base for report types that would otherwise scan the whole
        // directory. Report types that already require an explicit DN
        // (USERS_IN_BRANCH, USERS_IN_GROUP) ignore the override.
        String scope = scopeBaseDn(safeParams);

        OperationalReportType builtin = builtinOrNull(reportType);
        if (builtin != null) {
            return switch (builtin) {
                case USERS_IN_GROUP         -> runUsersInGroupReport(dc, safeParams);
                case USERS_IN_BRANCH        -> runLdapReport(dc,
                        "(|(objectClass=inetOrgPerson)(objectClass=person))",
                        requireString(safeParams, "branchDn"));
                case USERS_WITH_NO_GROUP    -> runUsersByGroupCountReport(dc, scope, safeParams);
                case RECENTLY_ADDED         -> runLdapReport(dc,
                        buildRecentFilter("createTimestamp", safeParams), scope);
                case RECENTLY_MODIFIED      -> runLdapReport(dc,
                        buildRecentFilter("modifyTimestamp", safeParams), scope);
                case RECENTLY_DELETED       -> runDeletedReport(directoryId, safeParams);
                case DISABLED_ACCOUNTS      -> runDisabledAccountsReport(dc, scope);
                case MISSING_PROFILE_GROUPS -> runMissingProfileGroupsReport(dc, directoryId);
                case AUDIT_ENTRIES          -> runAuditEntriesReport(directoryId, safeParams);
            };
        }

        // Addon-contributed report (e.g. the IVIA orphaned-account scan).
        OperationalReportProvider provider = reportProviders.stream()
                .filter(p -> p.reportId().equals(reportType))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown report type: " + reportType));
        if (!provider.appliesTo(dc)) {
            throw new IllegalArgumentException(
                    "Report '" + reportType + "' is not available for directory "
                            + dc.getDisplayName() + ".");
        }
        return provider.run(dc, safeParams, scope);
    }

    /** Resolve a built-in report type by name, or null if not a built-in. */
    private static OperationalReportType builtinOrNull(String reportType) {
        if (reportType == null) {
            return null;
        }
        try {
            return OperationalReportType.valueOf(reportType);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** Read the optional scopeBaseDn override from report params. */
    private static String scopeBaseDn(Map<String, Object> params) {
        Object raw = params.get("scopeBaseDn");
        if (raw == null) return null;
        String s = raw.toString().trim();
        return s.isEmpty() ? null : s;
    }

    // ── Per-type implementations ──────────────────────────────────────────────

    private ReportData runUsersInGroupReport(DirectoryConnection dc, Map<String, Object> params) {
        String groupDn = requireString(params, "groupDn");
        List<String> memberDns = new ArrayList<>();
        try {
            LdapGroup group = groupService.getGroup(dc, groupDn,
                    "member", "uniqueMember", "memberUid");
            memberDns.addAll(group.getAllMembers());
        } catch (Exception e) {
            log.warn("Could not read group {}: {}", groupDn, e.getMessage());
        }

        if (memberDns.isEmpty()) {
            return new ReportData(List.of("DN", "Name", "Email", "User ID"), List.of());
        }

        List<LdapUser> users = new ArrayList<>();
        for (String memberDn : memberDns) {
            try {
                users.addAll(userService.searchUsers(dc, "(objectClass=*)", memberDn, 1, "*"));
            } catch (Exception e) {
                log.debug("Skipping member {}: {}", memberDn, e.getMessage());
            }
        }
        return buildReportDataFromUsers(users);
    }

    /**
     * Two-pass implementation that doesn't rely on the memberOf overlay:
     * count how many groups each user appears in, then return the users whose
     * group count satisfies an operator/value comparison. When {@code scopeBaseDn}
     * is non-null, both the group scan and the user scan run under that base so
     * an admin only sees groups + users under their authorized OU.
     *
     * <p>The comparison is driven by two optional params:
     * {@code groupCountOp} (one of {@code =, !=, >, >=, <, <=}) and
     * {@code groupCountValue} (a non-negative integer). They default to
     * {@code "=" } and {@code 0} — i.e. "users with no group", the report's
     * original behaviour — so callers that pass no params are unaffected.
     * Passing {@code groupCountOp=">"} with {@code groupCountValue=1} returns
     * users belonging to more than one group.</p>
     */
    private ReportData runUsersByGroupCountReport(DirectoryConnection dc, String scopeBaseDn,
                                                  Map<String, Object> params) {
        String op = groupCountOp(params);
        int threshold = groupCountValue(params);

        String groupFilter = "(|(objectClass=groupOfNames)(objectClass=groupOfUniqueNames)"
                + "(objectClass=posixGroup)(objectClass=group))";
        List<LdapGroup> allGroups = groupService.searchGroups(dc, groupFilter, scopeBaseDn, MAX_LDAP_RESULTS,
                "member", "uniqueMember", "memberUid");

        Map<String, Integer> groupCounts = new HashMap<>();
        for (LdapGroup g : allGroups) {
            for (String m : g.getAllMembers()) {
                groupCounts.merge(m.toLowerCase(), 1, Integer::sum);
            }
        }

        List<LdapUser> allUsers = userService.searchUsers(dc,
                "(|(objectClass=inetOrgPerson)(objectClass=person))", scopeBaseDn,
                MAX_LDAP_RESULTS, "*");

        List<LdapUser> matched = allUsers.stream()
                .filter(u -> matchesGroupCount(
                        groupCounts.getOrDefault(u.getDn().toLowerCase(), 0), op, threshold))
                .toList();

        log.info("Users by group count ({} {}): {} matched out of {} total users ({} groups scanned)",
                op, threshold, matched.size(), allUsers.size(), allGroups.size());
        return buildReportDataFromUsers(matched);
    }

    /** Read the optional group-count comparison operator; defaults to "=". */
    private static String groupCountOp(Map<String, Object> params) {
        Object raw = params.get("groupCountOp");
        String op = raw == null ? "" : raw.toString().trim();
        return switch (op) {
            case "!=", ">", ">=", "<", "<=", "=" -> op;
            case "" -> "=";
            default -> throw new IllegalArgumentException(
                    "Unsupported groupCountOp: " + op + " (expected one of =, !=, >, >=, <, <=)");
        };
    }

    /** Read the optional group-count threshold; defaults to 0, never negative. */
    private static int groupCountValue(Map<String, Object> params) {
        Object raw = params.get("groupCountValue");
        if (raw == null || raw.toString().isBlank()) {
            return 0;
        }
        int value;
        try {
            value = (raw instanceof Number n) ? n.intValue() : Integer.parseInt(raw.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("groupCountValue must be an integer: " + raw);
        }
        if (value < 0) {
            throw new IllegalArgumentException("groupCountValue must not be negative: " + value);
        }
        return value;
    }

    private static boolean matchesGroupCount(int actual, String op, int threshold) {
        return switch (op) {
            case "=", ""  -> actual == threshold;
            case "!="     -> actual != threshold;
            case ">"      -> actual > threshold;
            case ">="     -> actual >= threshold;
            case "<"      -> actual < threshold;
            case "<="     -> actual <= threshold;
            default       -> actual == threshold;
        };
    }

    private ReportData runDisabledAccountsReport(DirectoryConnection dc, String scopeBaseDn) {
        // Keep the vendor-disable attribute set here in sync with
        // LdapDirectoryProvider.isEnabled — both code paths classify
        // accounts as disabled, and they must agree or operators see a
        // divergence between the directory browse view's badge and this
        // report. ds-pwp-account-disabled was added with OUD support;
        // without it here the disabled-accounts report silently misses
        // OUD/OpenDJ users disabled through the password-policy bit.
        String filter = dc.getDirectoryType() == DirectoryType.ACTIVE_DIRECTORY
                ? "(userAccountControl:1.2.840.113556.1.4.803:=2)"
                : "(|(pwdAccountLockedTime=*)(nsAccountLock=TRUE)"
                  + "(ds-pwp-account-disabled=TRUE)(loginDisabled=TRUE)"
                  + "(employeeType=Terminated)(loginShell=/sbin/nologin))";
        return runLdapReport(dc, filter, scopeBaseDn);
    }

    private ReportData runDeletedReport(UUID directoryId, Map<String, Object> params) {
        int lookbackDays = lookbackDays(params);
        OffsetDateTime from = OffsetDateTime.now().minusDays(lookbackDays);
        Object objectType = params.get("objectType");
        boolean includeUsers = objectType == null || objectType.toString().isBlank()
                || "USER".equalsIgnoreCase(objectType.toString());
        boolean includeGroups = objectType == null || objectType.toString().isBlank()
                || "GROUP".equalsIgnoreCase(objectType.toString());

        List<AuditEvent> allDeletes = new ArrayList<>();
        if (includeUsers) {
            allDeletes.addAll(auditEventRepo.findAll(directoryId, null,
                    AuditAction.USER_DELETE.name(), null, null, null, from, null,
                    Pageable.unpaged()).getContent());
        }
        if (includeGroups) {
            allDeletes.addAll(auditEventRepo.findAll(directoryId, null,
                    AuditAction.GROUP_DELETE.name(), null, null, null, from, null,
                    Pageable.unpaged()).getContent());
        }

        var changelogDeletes = auditEventRepo.findAll(directoryId, null,
                AuditAction.LDAP_CHANGE.name(), null, null, null, from, null, Pageable.unpaged());

        List<String> columns = List.of("Entry", "Deleted By", "Deleted At", "Source");
        List<Map<String, String>> rows = new ArrayList<>();
        for (AuditEvent e : allDeletes) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("Entry",      e.getTargetDn() != null ? e.getTargetDn() : "");
            row.put("Deleted By", e.getActorUsername() != null ? e.getActorUsername() : "");
            row.put("Deleted At", e.getOccurredAt() != null ? e.getOccurredAt().toString() : "");
            row.put("Source",     "Internal");
            rows.add(row);
        }
        changelogDeletes.getContent().stream()
                .filter(e -> e.getDetail() != null && isDeleteChange(e.getDetail()))
                .forEach(e -> {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("Entry",      e.getTargetDn() != null ? e.getTargetDn() : "");
                    row.put("Deleted By", "Changelog");
                    row.put("Deleted At", e.getOccurredAt() != null ? e.getOccurredAt().toString() : "");
                    row.put("Source",     "Changelog");
                    rows.add(row);
                });
        return new ReportData(columns, rows);
    }

    /**
     * Audit-entries report: the directory's audit events over a lookback window
     * (hours), optionally narrowed to specific {@link AuditAction}s — the same
     * data the Audit Log page shows, packaged as a runnable/exportable report.
     * Scoped to {@code directoryId} (like {@code RECENTLY_DELETED}). Capped at
     * {@link #MAX_AUDIT_RESULTS} rows, newest first.
     */
    private ReportData runAuditEntriesReport(UUID directoryId, Map<String, Object> params) {
        OffsetDateTime from = OffsetDateTime.now().minusHours(lookbackHours(params));
        String actionFilter = auditActionFilter(params);

        List<AuditEvent> events = auditEventRepo.findAll(
                directoryId, null, actionFilter, null, null, null, from, null,
                PageRequest.of(0, MAX_AUDIT_RESULTS)).getContent();

        List<String> columns = List.of("When", "Actor", "Action", "Target", "Detail");
        List<Map<String, String>> rows = new ArrayList<>();
        for (AuditEvent e : events) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("When",   e.getOccurredAt() != null ? e.getOccurredAt().toString() : "");
            row.put("Actor",  e.getActorUsername() != null ? e.getActorUsername() : "");
            row.put("Action", e.getAction() != null ? e.getAction().name() : "");
            row.put("Target", e.getTargetDn() != null ? e.getTargetDn() : "");
            row.put("Detail", formatAuditDetail(e.getDetail()));
            rows.add(row);
        }
        return new ReportData(columns, rows);
    }

    /** Lookback window in hours for the audit-entries report; defaults to 24, min 1. */
    private int lookbackHours(Map<String, Object> params) {
        Object raw = params.get("lookbackHours");
        if (raw instanceof Number n) return Math.max(1, n.intValue());
        if (raw instanceof String s) {
            try { return Math.max(1, Integer.parseInt(s.trim())); } catch (NumberFormatException ignored) { }
        }
        return 24;
    }

    /**
     * Optional action filter for the audit-entries report. Accepts a list (or a
     * comma-separated string) of {@link AuditAction} names and returns them
     * comma-joined for the repository's {@code string_to_array(...) = ANY} match
     * (the column stores enum names), or {@code null} for "all actions". Each
     * name is validated against the enum so a bad param is a 400, not a silent
     * no-match.
     */
    private static String auditActionFilter(Map<String, Object> params) {
        Object raw = params.get("actions");
        if (raw == null) return null;
        List<String> names = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !o.toString().isBlank()) names.add(o.toString().trim());
            }
        } else {
            String s = raw.toString().trim();
            if (s.isEmpty()) return null;
            for (String part : s.split("\\s*,\\s*")) {
                if (!part.isBlank()) names.add(part.trim());
            }
        }
        if (names.isEmpty()) return null;
        names.forEach(AuditAction::valueOf); // validate; throws IllegalArgumentException → 400
        return String.join(",", names);
    }

    private static String formatAuditDetail(Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) return "";
        return detail.entrySet().stream()
                .map(en -> en.getKey() + ": " + en.getValue())
                .collect(java.util.stream.Collectors.joining("; "));
    }

    private ReportData runMissingProfileGroupsReport(DirectoryConnection dc, UUID directoryId) {
        List<ProvisioningProfile> profiles =
                profileRepo.findAllByDirectoryIdAndEnabledTrue(directoryId);

        List<String> columns = List.of("User", "Profile", "Missing Group", "Attribute");
        List<Map<String, String>> rows = new ArrayList<>();

        for (ProvisioningProfile profile : profiles) {
            try {
                GroupChangePreview preview =
                        profileService.evaluateGroupChanges(directoryId, profile.getId());
                for (GroupChangePreview.UserGroupChange change : preview.changes()) {
                    for (GroupChangePreview.GroupChange add : change.groupsToAdd()) {
                        Map<String, String> row = new LinkedHashMap<>();
                        row.put("User", change.userDn());
                        row.put("Profile", profile.getName());
                        row.put("Missing Group", add.groupDn());
                        row.put("Attribute", add.memberAttribute());
                        rows.add(row);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to evaluate group changes for profile {}: {}",
                        profile.getName(), e.getMessage());
            }
        }
        return new ReportData(columns, rows);
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    private ReportData runLdapReport(DirectoryConnection dc, String filter, String baseDn) {
        List<LdapUser> users = userService.searchUsers(dc, filter, baseDn, MAX_LDAP_RESULTS, "*");
        if (users.size() >= MAX_LDAP_RESULTS) {
            log.warn("Report query hit the {} result limit — results may be truncated. "
                    + "Filter: {}, baseDn: {}", MAX_LDAP_RESULTS, filter, baseDn);
        }
        return buildReportDataFromUsers(users);
    }

    private ReportData buildReportDataFromUsers(List<LdapUser> users) {
        TreeSet<String> attrNames = new TreeSet<>();
        users.forEach(u -> attrNames.addAll(u.getAttributes().keySet()));
        List<String> rawColumns = new ArrayList<>();
        rawColumns.add("dn");
        rawColumns.addAll(attrNames);

        List<String> columns = rawColumns.stream().map(OperationalReportService::friendlyLdapColumn).toList();
        List<Map<String, String>> rows = users.stream()
                .map(u -> buildFriendlyRow(u, rawColumns))
                .toList();
        return new ReportData(columns, rows);
    }

    private Map<String, String> buildFriendlyRow(LdapUser user, List<String> rawColumns) {
        Map<String, String> row = new LinkedHashMap<>();
        for (String col : rawColumns) {
            String friendly = friendlyLdapColumn(col);
            if ("dn".equals(col)) {
                row.put(friendly, user.getDn());
            } else {
                row.put(friendly, String.join("|", user.getValues(col)));
            }
        }
        return row;
    }

    private static final Map<String, String> LDAP_COLUMN_NAMES = Map.ofEntries(
            Map.entry("dn", "DN"),
            Map.entry("cn", "Name"),
            Map.entry("uid", "User ID"),
            Map.entry("mail", "Email"),
            Map.entry("sn", "Last Name"),
            Map.entry("givenname", "First Name"),
            Map.entry("displayname", "Display Name"),
            Map.entry("telephonenumber", "Phone"),
            Map.entry("title", "Title"),
            Map.entry("description", "Description"),
            Map.entry("objectclass", "Object Class"),
            Map.entry("createtimestamp", "Created"),
            Map.entry("modifytimestamp", "Modified"),
            Map.entry("employeenumber", "Employee #"),
            Map.entry("employeetype", "Employee Type"),
            Map.entry("departmentnumber", "Dept #"),
            Map.entry("o", "Organization"),
            Map.entry("ou", "Org Unit"),
            Map.entry("l", "Location"),
            Map.entry("st", "State"),
            Map.entry("postalcode", "Postal Code"),
            Map.entry("street", "Street"),
            Map.entry("memberof", "Member Of"),
            Map.entry("manager", "Manager"),
            Map.entry("loginshell", "Login Shell"),
            Map.entry("homedirectory", "Home Dir"),
            Map.entry("uidnumber", "UID #"),
            Map.entry("gidnumber", "GID #"),
            Map.entry("useraccountcontrol", "UAC"),
            Map.entry("samaccountname", "SAM Account"),
            Map.entry("userprincipalname", "UPN"),
            Map.entry("pwdaccountlockedtime", "Locked Since"),
            Map.entry("nsaccountlock", "Account Lock"),
            Map.entry("userpassword", "Password"),
            Map.entry("entryuuid", "Entry UUID"),
            Map.entry("entrydn", "Entry DN"),
            Map.entry("structuralobjectclass", "Structural Class"),
            Map.entry("subschemasubentry", "Subschema"),
            Map.entry("hassubordinates", "Has Children"),
            Map.entry("numsubordinates", "# Children")
    );

    static String friendlyLdapColumn(String raw) {
        String friendly = LDAP_COLUMN_NAMES.get(raw.toLowerCase());
        return friendly != null ? friendly : raw;
    }

    private boolean isDeleteChange(Map<String, Object> detail) {
        Object changeType = detail.get("changeType");
        if (changeType != null && changeType.toString().equalsIgnoreCase("delete")) return true;
        Object changes = detail.get("changes");
        return changes != null && changes.toString().toLowerCase().contains("changetype: delete");
    }

    private String buildRecentFilter(String timestampAttr, Map<String, Object> params) {
        String ts = lookbackTimestamp(params);
        String timeFilter = "(" + timestampAttr + ">=" + ts + ")";
        Object objectType = params.get("objectType");
        if (objectType == null || objectType.toString().isBlank()) {
            return timeFilter;
        }
        String typeFilter = switch (objectType.toString().toUpperCase()) {
            case "USER" -> "(|(objectClass=inetOrgPerson)(&(objectClass=user)(!(objectClass=computer))))";
            case "GROUP" -> "(|(objectClass=groupOfNames)(objectClass=groupOfUniqueNames)(objectClass=posixGroup)(objectClass=group))";
            default -> "";
        };
        if (typeFilter.isEmpty()) return timeFilter;
        return "(&" + timeFilter + typeFilter + ")";
    }

    private String lookbackTimestamp(Map<String, Object> params) {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(lookbackDays(params));
        return cutoff.withOffsetSameInstant(ZoneOffset.UTC).format(LDAP_TIMESTAMP_FMT);
    }

    private int lookbackDays(Map<String, Object> params) {
        Object raw = params.get("lookbackDays");
        if (raw instanceof Number n) return n.intValue();
        if (raw instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) { }
        }
        return 30;
    }

    private String requireString(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(
                    "Report parameter '" + key + "' is required for this report type");
        }
        return value.toString();
    }

    private void requireLdapDirectory(DirectoryConnection dc) {
        if (dc.getDirectoryType() == DirectoryType.ENTRA_ID) {
            throw new IllegalArgumentException(
                    "Reports are not supported for Entra ID directories. "
                    + "Use the Entra ID Browser to view cached user and group data.");
        }
    }
}
