// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PermissionService;
import com.ldapportal.dto.csv.BulkMembershipPreviewResult;
import com.ldapportal.dto.csv.BulkMembershipPreviewRow;
import com.ldapportal.dto.csv.BulkMembershipPreviewRow.Disposition;
import com.ldapportal.dto.csv.BulkMembershipResult;
import com.ldapportal.dto.csv.BulkMembershipRowResult;
import com.ldapportal.dto.ldap.MembershipChangeRequest;
import com.ldapportal.dto.ldap.MembershipChangeResult;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.DirectoryObjectClassDefaults;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.ldap.LdapGroupService;
import com.ldapportal.ldap.LdapUserService;
import com.ldapportal.ldap.model.LdapGroup;
import com.ldapportal.ldap.model.LdapUser;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.util.CsvUtils;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Bulk group membership from a CSV: each row names a user and the groups to
 * add them to (or remove them from).
 *
 * <h3>File format</h3>
 * <p>A header row is required. Columns are matched case-insensitively:</p>
 * <ul>
 *   <li>{@code user} — the user's uid (looked up within the caller's scope),
 *       or a full DN.</li>
 *   <li>{@code groups} (or {@code group}) — one or more groups separated by
 *       {@code ;} or {@code |}, each a cn or a full DN.</li>
 *   <li>{@code op} — optional, {@code ADD} (default) or {@code REMOVE}.</li>
 * </ul>
 * <p>Both "one row per user, many groups" and "one row per user-group pair"
 * files work: rows are expanded into pairs and the same user may appear on
 * any number of rows.</p>
 *
 * <p>Preview and commit share one resolution pass, so the preview shows
 * exactly what commit writes. Commit re-resolves (the directory may have
 * changed since the preview) and applies each user's changes through
 * {@link LdapOperationService#applyMembershipChanges}, which brings the same
 * approval gating, Separation-of-Duties checks and per-change audit records
 * as the single-user membership endpoint.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BulkMembershipService {

    /** Per-request cap on expanded user-group pairs; larger files must be split. */
    static final int MAX_PAIRS = 2000;

    private static final Pattern GROUP_SEPARATOR = Pattern.compile("[;|]");
    private static final String[] GROUP_ATTRS =
            {"objectClass", "cn", "member", "uniqueMember", "memberUid"};

    private final DirectoryConnectionRepository dirRepo;
    private final PermissionService permissionService;
    private final LdapUserService userService;
    private final LdapGroupService groupService;
    private final LdapOperationService operationService;

    // ── public API ──────────────────────────────────────────────────────────

    public BulkMembershipPreviewResult preview(UUID directoryId, AuthPrincipal principal,
                                               InputStream csv) throws IOException {
        List<ResolvedPair> pairs = resolve(directoryId, principal, csv);
        List<BulkMembershipPreviewRow> rows = pairs.stream()
                .map(p -> new BulkMembershipPreviewRow(p.rowNumber, p.user, p.userDn(),
                        p.group, p.groupDn(), p.op, p.disposition, p.note))
                .toList();
        int actionable = (int) pairs.stream().filter(p -> p.disposition.actionable()).count();
        return new BulkMembershipPreviewResult(rows.size(), actionable, rows);
    }

    public BulkMembershipResult apply(UUID directoryId, AuthPrincipal principal,
                                      InputStream csv) throws IOException {
        List<ResolvedPair> pairs = resolve(directoryId, principal, csv);

        // One applyMembershipChanges call per user, in file order.
        Map<String, List<ResolvedPair>> byUser = new LinkedHashMap<>();
        for (ResolvedPair p : pairs) {
            if (p.disposition.actionable()) {
                byUser.computeIfAbsent(p.resolvedUser.dn, k -> new ArrayList<>()).add(p);
            }
        }

        Map<ResolvedPair, BulkMembershipRowResult> results = new HashMap<>();
        for (List<ResolvedPair> userPairs : byUser.values()) {
            ResolvedUser u = userPairs.get(0).resolvedUser;
            List<MembershipChangeRequest.Change> changes = userPairs.stream()
                    .map(p -> new MembershipChangeRequest.Change(
                            p.resolvedGroup.dn, p.resolvedGroup.memberAttribute, p.op))
                    .toList();
            MembershipChangeResult outcome = operationService.applyMembershipChanges(
                    directoryId, principal, u.dn, u.uid, changes);
            for (ResolvedPair p : userPairs) {
                results.put(p, toRowResult(p, findItem(outcome, p)));
            }
        }

        List<BulkMembershipRowResult> rows = new ArrayList<>(pairs.size());
        for (ResolvedPair p : pairs) {
            BulkMembershipRowResult r = results.get(p);
            rows.add(r != null ? r : notApplied(p));
        }
        long applied = count(rows, BulkMembershipRowResult.Status.APPLIED);
        long queued  = count(rows, BulkMembershipRowResult.Status.QUEUED_FOR_APPROVAL);
        long skipped = count(rows, BulkMembershipRowResult.Status.SKIPPED);
        long failed  = rows.size() - applied - queued - skipped;
        log.info("Bulk membership complete: {} pairs — applied={}, queued={}, skipped={}, failed={}",
                rows.size(), applied, queued, skipped, failed);
        return new BulkMembershipResult(rows.size(), applied, queued, skipped, failed, rows);
    }

    // ── resolution (shared by preview and commit) ──────────────────────────

    private List<ResolvedPair> resolve(UUID directoryId, AuthPrincipal principal,
                                       InputStream csv) throws IOException {
        DirectoryConnection dc = dirRepo.findById(directoryId)
                .filter(DirectoryConnection::isEnabled)
                .orElseThrow(() -> new ResourceNotFoundException("DirectoryConnection", directoryId));
        permissionService.requireDirectoryAccess(principal, directoryId);

        List<ResolvedPair> pairs = parse(csv);
        if (pairs.size() > MAX_PAIRS) {
            throw new IllegalArgumentException("The file expands to " + pairs.size()
                    + " user-group pairs; the limit is " + MAX_PAIRS + " per upload — split the file");
        }

        // The admin's search bases (their profile OUs; the directory root for a
        // superadmin), resolved once and reused for every uid / cn lookup.
        List<String> bases = permissionService.resolveSearchBaseDns(principal, directoryId, null);
        Resolver resolver = new Resolver(dc, directoryId, principal, bases);

        for (ResolvedPair p : pairs) {
            if (p.disposition != null) continue; // INVALID from parsing
            p.resolvedUser = resolver.user(p.user);
            p.resolvedGroup = resolver.group(p.group);
            classify(p);
        }
        markDuplicatesAndConflicts(pairs);
        return pairs;
    }

    /** Expands every CSV row into one pair per group. */
    private List<ResolvedPair> parse(InputStream csv) throws IOException {
        List<Map<String, String>> rows = CsvUtils.parse(csv, true);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("The file has no data rows");
        }
        Set<String> headers = rows.get(0).keySet();
        String userCol  = column(headers, "user");
        String groupCol = column(headers, "groups") != null
                ? column(headers, "groups") : column(headers, "group");
        String opCol    = column(headers, "op");
        if (userCol == null || groupCol == null) {
            throw new IllegalArgumentException(
                    "The file needs a 'user' column and a 'groups' (or 'group') column");
        }

        List<ResolvedPair> out = new ArrayList<>();
        int rowNumber = 0;
        for (Map<String, String> row : rows) {
            rowNumber++;
            String user = trim(row.get(userCol));
            String rawOp = opCol == null ? null : trim(row.get(opCol));
            MembershipChangeRequest.Op op = parseOp(rawOp);
            List<String> groups = new ArrayList<>();
            String rawGroups = row.get(groupCol);
            if (rawGroups != null) {
                for (String g : GROUP_SEPARATOR.split(rawGroups)) {
                    if (!g.isBlank()) groups.add(g.trim());
                }
            }

            if (user == null || groups.isEmpty()) {
                if (user == null && groups.isEmpty()) continue; // blank line
                out.add(ResolvedPair.invalid(rowNumber, user, groups.isEmpty() ? null : groups.get(0),
                        op, user == null ? "No user on this row" : "No groups on this row"));
                continue;
            }
            for (String g : groups) {
                out.add(op == null
                        ? ResolvedPair.invalid(rowNumber, user, g, null,
                                "Unknown op '" + rawOp + "' — use ADD or REMOVE")
                        : new ResolvedPair(rowNumber, user, g, op));
            }
        }
        return out;
    }

    private static void classify(ResolvedPair p) {
        ResolvedUser u = p.resolvedUser;
        ResolvedGroup g = p.resolvedGroup;
        if (u.problem != null) {
            p.set(u.problem, u.note);
        } else if (g.problem != null) {
            p.set(g.problem, g.note);
        } else if ("memberUid".equalsIgnoreCase(g.memberAttribute) && u.uid == null) {
            p.set(Disposition.INVALID, "Group uses memberUid but this user has no uid");
        } else {
            boolean member = g.hasMember(u);
            if (p.op == MembershipChangeRequest.Op.ADD) {
                p.set(member ? Disposition.ALREADY_MEMBER : Disposition.WILL_ADD,
                        member ? "Already a member" : null);
            } else {
                p.set(member ? Disposition.WILL_REMOVE : Disposition.NOT_A_MEMBER,
                        member ? null : "Not a member");
            }
        }
    }

    /**
     * The same resolved user-group pair twice: same op → later rows are
     * DUPLICATE; ADD and REMOVE of the same pair → every row for it INVALID.
     */
    private static void markDuplicatesAndConflicts(List<ResolvedPair> pairs) {
        Map<String, List<ResolvedPair>> byKey = new LinkedHashMap<>();
        for (ResolvedPair p : pairs) {
            if (p.userDn() == null || p.groupDn() == null) continue;
            byKey.computeIfAbsent(normalize(p.userDn()) + "\n" + normalize(p.groupDn()),
                    k -> new ArrayList<>()).add(p);
        }
        for (List<ResolvedPair> same : byKey.values()) {
            if (same.size() < 2) continue;
            boolean conflicting = same.stream().map(p -> p.op).distinct().count() > 1;
            for (int i = 0; i < same.size(); i++) {
                ResolvedPair p = same.get(i);
                if (conflicting) {
                    p.set(Disposition.INVALID, "The file both adds and removes this membership");
                } else if (i > 0) {
                    p.set(Disposition.DUPLICATE, "Repeats row " + same.get(0).rowNumber);
                }
            }
        }
    }

    // ── commit result mapping ───────────────────────────────────────────────

    private static MembershipChangeResult.Item findItem(MembershipChangeResult outcome, ResolvedPair p) {
        return outcome.items().stream()
                .filter(i -> i.op() == p.op && i.groupDn().equalsIgnoreCase(p.resolvedGroup.dn))
                .findFirst()
                .orElse(null);
    }

    private static BulkMembershipRowResult toRowResult(ResolvedPair p, MembershipChangeResult.Item item) {
        if (item == null) {
            return row(p, BulkMembershipRowResult.Status.ERROR, null, "No result returned for this change");
        }
        BulkMembershipRowResult.Status status = switch (item.status()) {
            case APPLIED -> BulkMembershipRowResult.Status.APPLIED;
            case QUEUED_FOR_APPROVAL -> BulkMembershipRowResult.Status.QUEUED_FOR_APPROVAL;
            case REFUSED -> BulkMembershipRowResult.Status.REFUSED;
            case BLOCKED -> BulkMembershipRowResult.Status.BLOCKED;
            case ERROR -> BulkMembershipRowResult.Status.ERROR;
        };
        return row(p, status, item.approvalId(), item.message());
    }

    /** A pair that wasn't written: no-ops and duplicates skip, everything else errors. */
    private static BulkMembershipRowResult notApplied(ResolvedPair p) {
        BulkMembershipRowResult.Status status = switch (p.disposition) {
            case ALREADY_MEMBER, NOT_A_MEMBER, DUPLICATE -> BulkMembershipRowResult.Status.SKIPPED;
            default -> BulkMembershipRowResult.Status.ERROR;
        };
        return row(p, status, null, p.note);
    }

    private static BulkMembershipRowResult row(ResolvedPair p, BulkMembershipRowResult.Status status,
                                               UUID approvalId, String message) {
        return new BulkMembershipRowResult(p.rowNumber, p.user, p.userDn(), p.group, p.groupDn(),
                p.op, status, approvalId, message);
    }

    private static long count(List<BulkMembershipRowResult> rows, BulkMembershipRowResult.Status s) {
        return rows.stream().filter(r -> r.status() == s).count();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static String column(Set<String> headers, String name) {
        return headers.stream()
                // Excel's "CSV UTF-8" prefixes the first header with a byte-order mark.
                .filter(h -> h != null && h.replace("\uFEFF", "").trim().equalsIgnoreCase(name))
                .findFirst().orElse(null);
    }

    private static MembershipChangeRequest.Op parseOp(String raw) {
        if (raw == null || raw.isBlank()) return MembershipChangeRequest.Op.ADD;
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "ADD" -> MembershipChangeRequest.Op.ADD;
            case "REMOVE" -> MembershipChangeRequest.Op.REMOVE;
            default -> null;
        };
    }

    private static String trim(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** A value is treated as a DN when it has an attribute=value form. */
    private static boolean looksLikeDn(String v) {
        return v.indexOf('=') > 0;
    }

    /** Canonical DN for comparisons; falls back to lower-case on a malformed value. */
    static String normalize(String dn) {
        try {
            return new DN(dn).toNormalizedString();
        } catch (LDAPException e) {
            return dn.trim().toLowerCase(Locale.ROOT);
        }
    }

    /** Looks users and groups up once per distinct CSV value. */
    private final class Resolver {
        private final DirectoryConnection dc;
        private final UUID directoryId;
        private final AuthPrincipal principal;
        private final List<String> bases;
        private final Map<String, ResolvedUser> users = new HashMap<>();
        private final Map<String, ResolvedGroup> groups = new HashMap<>();

        Resolver(DirectoryConnection dc, UUID directoryId, AuthPrincipal principal, List<String> bases) {
            this.dc = dc;
            this.directoryId = directoryId;
            this.principal = principal;
            this.bases = bases;
        }

        ResolvedUser user(String value) {
            return users.computeIfAbsent(value.toLowerCase(Locale.ROOT), k -> lookupUser(value));
        }

        ResolvedGroup group(String value) {
            return groups.computeIfAbsent(value.toLowerCase(Locale.ROOT), k -> lookupGroup(value));
        }

        private ResolvedUser lookupUser(String value) {
            try {
                if (looksLikeDn(value)) {
                    if (!permissionService.isDnWithinScope(principal, directoryId, value)) {
                        return ResolvedUser.problem(Disposition.OUT_OF_SCOPE, "User is outside your authorized scope");
                    }
                    LdapUser u = userService.getUser(dc, value, "uid");
                    return new ResolvedUser(u.getDn(), u.getFirstValue("uid"));
                }
                String filter = "(&" + Filter.createEqualityFilter("uid", value)
                        + DirectoryObjectClassDefaults.userSearchFilter(dc) + ")";
                Set<String> dns = new LinkedHashSet<>();
                for (String base : bases) {
                    userService.searchUsers(dc, filter, base, 2, "1.1").forEach(u -> dns.add(u.getDn()));
                }
                if (dns.isEmpty()) {
                    return ResolvedUser.problem(Disposition.USER_NOT_FOUND, "No user with uid=" + value);
                }
                if (dns.size() > 1) {
                    return ResolvedUser.problem(Disposition.AMBIGUOUS, "uid=" + value + " matches more than one user");
                }
                String dn = dns.iterator().next();
                if (!permissionService.isDnWithinScope(principal, directoryId, dn)) {
                    return ResolvedUser.problem(Disposition.OUT_OF_SCOPE, "User is outside your authorized scope");
                }
                return new ResolvedUser(dn, value);
            } catch (ResourceNotFoundException e) {
                return ResolvedUser.problem(Disposition.USER_NOT_FOUND, "No entry at this DN");
            } catch (RuntimeException e) {
                return ResolvedUser.problem(Disposition.INVALID, e.getMessage());
            }
        }

        private ResolvedGroup lookupGroup(String value) {
            try {
                String dn;
                if (looksLikeDn(value)) {
                    dn = value;
                } else {
                    String filter = "(&" + Filter.createEqualityFilter("cn", value)
                            + DirectoryObjectClassDefaults.groupSearchFilter(dc) + ")";
                    Set<String> dns = new LinkedHashSet<>();
                    for (String base : bases) {
                        groupService.searchGroups(dc, filter, base, 2, "1.1").forEach(g -> dns.add(g.getDn()));
                    }
                    if (dns.isEmpty()) {
                        return ResolvedGroup.problem(Disposition.GROUP_NOT_FOUND, "No group with cn=" + value);
                    }
                    if (dns.size() > 1) {
                        return ResolvedGroup.problem(Disposition.AMBIGUOUS, "cn=" + value + " matches more than one group");
                    }
                    dn = dns.iterator().next();
                }
                if (!permissionService.isDnWithinScope(principal, directoryId, dn)) {
                    return ResolvedGroup.problem(Disposition.OUT_OF_SCOPE, "Group is outside your authorized scope");
                }
                LdapGroup g = groupService.getGroup(dc, dn, GROUP_ATTRS);
                Set<String> groupClasses = DirectoryObjectClassDefaults.effectiveGroupObjectClassSet(dc);
                boolean isGroup = g.getValues("objectClass").stream()
                        .anyMatch(oc -> groupClasses.contains(oc.toLowerCase(Locale.ROOT)));
                if (!isGroup) {
                    return ResolvedGroup.problem(Disposition.INVALID, "Entry is not a group");
                }
                return ResolvedGroup.of(g);
            } catch (ResourceNotFoundException e) {
                return ResolvedGroup.problem(Disposition.GROUP_NOT_FOUND, "No group at this DN");
            } catch (RuntimeException e) {
                return ResolvedGroup.problem(Disposition.INVALID, e.getMessage());
            }
        }
    }

    // ── internal model ──────────────────────────────────────────────────────

    private static final class ResolvedPair {
        final int rowNumber;
        final String user;
        final String group;
        final MembershipChangeRequest.Op op;
        ResolvedUser resolvedUser;
        ResolvedGroup resolvedGroup;
        Disposition disposition;
        String note;

        ResolvedPair(int rowNumber, String user, String group, MembershipChangeRequest.Op op) {
            this.rowNumber = rowNumber;
            this.user = user;
            this.group = group;
            this.op = op;
        }

        static ResolvedPair invalid(int rowNumber, String user, String group,
                                    MembershipChangeRequest.Op op, String note) {
            ResolvedPair p = new ResolvedPair(rowNumber, user, group, op);
            p.set(Disposition.INVALID, note);
            return p;
        }

        void set(Disposition d, String n) {
            this.disposition = d;
            this.note = n;
        }

        String userDn() {
            return resolvedUser == null ? null : resolvedUser.dn;
        }

        String groupDn() {
            return resolvedGroup == null ? null : resolvedGroup.dn;
        }
    }

    private record ResolvedUser(String dn, String uid, Disposition problem, String note) {
        ResolvedUser(String dn, String uid) {
            this(dn, uid, null, null);
        }

        static ResolvedUser problem(Disposition d, String note) {
            return new ResolvedUser(null, null, d, note);
        }
    }

    private record ResolvedGroup(String dn, String memberAttribute, Set<String> memberDns,
                                 Set<String> memberUids, Disposition problem, String note) {

        static ResolvedGroup problem(Disposition d, String note) {
            return new ResolvedGroup(null, null, Set.of(), Set.of(), d, note);
        }

        /**
         * Picks the attribute members are written to: whichever one the group
         * already uses, else what its object classes imply (groupOfUniqueNames
         * → uniqueMember, a pure posixGroup → memberUid, otherwise member).
         */
        static ResolvedGroup of(LdapGroup g) {
            String attr;
            if (!g.getUniqueMember().isEmpty()) {
                attr = "uniqueMember";
            } else if (!g.getMember().isEmpty()) {
                attr = "member";
            } else if (!g.getMemberUid().isEmpty()) {
                attr = "memberUid";
            } else {
                Set<String> ocs = new java.util.HashSet<>();
                g.getValues("objectClass").forEach(oc -> ocs.add(oc.toLowerCase(Locale.ROOT)));
                if (ocs.contains("groupofuniquenames")) {
                    attr = "uniqueMember";
                } else if (ocs.contains("posixgroup") && !ocs.contains("groupofnames") && !ocs.contains("group")) {
                    attr = "memberUid";
                } else {
                    attr = "member";
                }
            }
            Set<String> dns = new java.util.HashSet<>();
            g.getMember().forEach(m -> dns.add(normalize(m)));
            g.getUniqueMember().forEach(m -> dns.add(normalize(m)));
            Set<String> uids = new java.util.HashSet<>();
            g.getMemberUid().forEach(m -> uids.add(m.toLowerCase(Locale.ROOT)));
            return new ResolvedGroup(g.getDn(), attr, dns, uids, null, null);
        }

        boolean hasMember(ResolvedUser u) {
            if ("memberUid".equalsIgnoreCase(memberAttribute)) {
                return u.uid() != null && memberUids.contains(u.uid().toLowerCase(Locale.ROOT));
            }
            return memberDns.contains(normalize(u.dn()));
        }
    }
}
