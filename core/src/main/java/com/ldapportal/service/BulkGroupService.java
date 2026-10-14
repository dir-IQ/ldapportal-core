// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.dto.csv.BulkImportPreviewResult;
import com.ldapportal.dto.csv.BulkImportPreviewRow;
import com.ldapportal.dto.csv.BulkImportPreviewRow.GroupAction;
import com.ldapportal.dto.csv.BulkImportResult;
import com.ldapportal.dto.csv.BulkImportRowResult;
import com.ldapportal.dto.csv.CsvColumnMappingDto;
import com.ldapportal.dto.csv.GroupAttributeChange;
import com.ldapportal.dto.csv.GroupMemberChange;
import com.ldapportal.dto.csv.GroupMemberChange.Outcome;
import com.ldapportal.dto.ldap.MembershipChangeRequest.Op;
import com.ldapportal.dto.ldap.MembershipChangeResult;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.ldap.LdapGroupService;
import com.ldapportal.ldap.model.LdapGroup;
import com.ldapportal.service.GroupImportMembers.ExistingGroup;
import com.ldapportal.service.GroupImportMembers.Member;
import com.ldapportal.util.CsvUtils;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.unboundid.ldap.sdk.RDN;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * CSV parsing and generation service for bulk group import and export operations.
 *
 * <h3>Import columns</h3>
 * <ul>
 *   <li>{@code cn} — required; the group DN is always {@code cn=<cn>,<parentDn>}.</li>
 *   <li>{@code members} (or a column mapped to the member attribute) — members
 *       separated by {@code |}, each a uid (looked up within the caller's
 *       scope) or a full DN.</li>
 *   <li>{@value #MEMBER_OPERATION_COLUMN} — {@code add} (the default when the
 *       column is absent or blank) creates the group or adds the row's members
 *       to it; {@code delete} removes the row's members from an existing group
 *       and changes nothing else.</li>
 *   <li>{@code dn} — ignored, so a file written by export re-imports as-is.</li>
 *   <li>anything else ({@code description}, {@code owner}, …) — an attribute
 *       (see {@link CsvColumnMap}). A new group is created with it; on an
 *       existing group a provided value replaces the current one, and a blank
 *       or absent value leaves the attribute as it is.</li>
 * </ul>
 *
 * <h3>Preview and import share one plan</h3>
 * <p>Every row is resolved before anything is written: whether its group will
 * be created or updated, which entry each member value names, and whether each
 * change is needed (already a member, not a member), repeated in the file, or
 * contradicted by another row (the same membership added and removed). Preview
 * returns that plan; import builds it again (the directory may have changed)
 * and carries it out.</p>
 *
 * <h3>How members are written</h3>
 * <ul>
 *   <li>A group the file creates gets its members in the create itself
 *       ({@code groupOfNames} can't exist without one). The file-level bulk
 *       import approval covers it.</li>
 *   <li>Changes to an existing group go one at a time through the governed
 *       membership path ({@link GroupImportMembers#apply}), so approval rules,
 *       Separation-of-Duties blocks, interceptor refusals and per-change audit
 *       records apply exactly as for a manual edit.</li>
 *   <li>Members go to the attribute the existing group already uses
 *       ({@code member} / {@code uniqueMember} / {@code memberUid}, the latter
 *       taking the uid); a new group uses the import's member attribute.</li>
 * </ul>
 * <p>A file that lists any members also needs {@code group.manage_members}
 * (checked once, before anything is resolved or written).</p>
 *
 * <p>There is no conflict-handling setting: an existing group is always
 * updated with the attributes a row provides (only those that differ are
 * written), and its members are added or removed per
 * {@value #MEMBER_OPERATION_COLUMN}.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BulkGroupService {

    /** Reserved column choosing what a row does with its members. */
    static final String MEMBER_OPERATION_COLUMN = "member-operation";

    /** Per-file cap on member changes; larger files must be split. */
    static final int MAX_MEMBER_CHANGES = 2000;

    private final LdapGroupService groupService;

    // ── Import ────────────────────────────────────────────────────────────────

    /**
     * Plans and carries out a group import.
     *
     * @param dc                  target directory connection
     * @param csvInput            raw CSV byte stream (UTF-8)
     * @param parentDn            container new groups are created in
     * @param columnMappings      CSV column → LDAP attribute mappings
     * @param objectClasses       objectClass values for new groups
     * @param memberAttribute     member attribute for new groups (member, uniqueMember, memberUid)
     * @param skipHeaderRow       whether the first row is a header
     * @param fieldDelimiter      field delimiter (comma, semicolon, tab, ...)
     * @param passthroughUnmapped {@code false} (template-driven imports) drops
     *                            columns the mappings don't mention; see {@link CsvColumnMap}
     * @param members             member resolution and governed writes for the caller
     */
    public BulkImportResult importCsv(DirectoryConnection dc,
                                       InputStream csvInput,
                                       String parentDn,
                                       List<CsvColumnMappingDto> columnMappings,
                                       List<String> objectClasses,
                                       String memberAttribute,
                                       boolean skipHeaderRow,
                                       char fieldDelimiter,
                                       boolean passthroughUnmapped,
                                       GroupImportMembers members) throws IOException {

        CsvColumnMap colToAttr = CsvColumnMap.of(columnMappings, passthroughUnmapped);
        List<Map<String, String>> rows = parse(csvInput, skipHeaderRow, fieldDelimiter);
        List<RowPlan> plans = plan(rows, colToAttr, parentDn, objectClasses, memberAttribute, members);

        List<BulkImportRowResult> rowResults = new ArrayList<>(plans.size());
        for (RowPlan p : plans) {
            rowResults.add(execute(dc, p, members));
        }

        long created = countByStatus(rowResults, BulkImportRowResult.Status.CREATED);
        long updated = countByStatus(rowResults, BulkImportRowResult.Status.UPDATED);
        long skipped = countByStatus(rowResults, BulkImportRowResult.Status.SKIPPED);
        long errors  = countByStatus(rowResults, BulkImportRowResult.Status.ERROR);

        log.info("Bulk group import complete: {} rows — created={}, updated={}, skipped={}, errors={}",
                plans.size(), created, updated, skipped, errors);

        return new BulkImportResult(plans.size(), created, updated, skipped, errors, rowResults);
    }

    // ── Preview ───────────────────────────────────────────────────────────────

    /**
     * Builds the import plan without writing anything.
     *
     * @param requiredAttrs LDAP attribute names the chosen group object class
     *                      marks as MUST, checked on rows that create a group.
     *                      Empty list = no validation.
     * @param memberAttr    the member attribute a new group is created with, so
     *                      a row's members count as supplying it
     */
    public BulkImportPreviewResult previewImport(InputStream csvInput,
                                                  String parentDn,
                                                  List<CsvColumnMappingDto> columnMappings,
                                                  boolean skipHeaderRow,
                                                  List<String> requiredAttrs,
                                                  String memberAttr,
                                                  char fieldDelimiter,
                                                  boolean passthroughUnmapped,
                                                  GroupImportMembers members) throws IOException {

        CsvColumnMap colToAttr = CsvColumnMap.of(columnMappings, passthroughUnmapped);
        List<Map<String, String>> rows = parse(csvInput, skipHeaderRow, fieldDelimiter);
        List<RowPlan> plans = plan(rows, colToAttr, parentDn, List.of(), memberAttr, members);

        List<String> required = requiredAttrs == null ? List.of() : requiredAttrs;
        List<BulkImportPreviewRow> previewRows = new ArrayList<>(plans.size());
        for (RowPlan p : plans) {
            List<String> missing = new ArrayList<>();
            // A missing cn (the group RDN) and a bad member-operation are shown
            // the same way as schema MUSTs: amber highlight + Issues column.
            if (p.dn == null) missing.add("cn");
            if (p.operation == null) missing.add(MEMBER_OPERATION_COLUMN);
            // Only a row that creates the group needs the object class's MUSTs.
            if (p.action == GroupAction.CREATE) {
                Set<String> present = new HashSet<>();
                p.attrs.keySet().forEach(a -> present.add(a.toLowerCase(Locale.ROOT)));
                if (p.changes.stream().anyMatch(c -> c.outcome == Outcome.WILL_ADD)) {
                    present.add(p.memberAttribute.toLowerCase(Locale.ROOT));
                }
                required.stream()
                        .filter(a -> !present.contains(a.toLowerCase(Locale.ROOT)))
                        .forEach(missing::add);
            }
            previewRows.add(new BulkImportPreviewRow(p.rowNumber, p.dn, p.displayAttrs, missing,
                    p.error == null ? p.action : null, p.error, p.changeRows(),
                    p.error == null ? p.attrUpdates : List.of()));
        }

        List<String> unmapped = colToAttr.unmappedColumns(rows).stream()
                .filter(c -> !isMemberOperationColumn(c))
                .toList();
        return new BulkImportPreviewResult(plans.size(), previewRows, unmapped);
    }

    /**
     * Whether the file lists any members, i.e. would add or remove members.
     * Lets the caller check {@code group.manage_members} before queueing a
     * file for approval, without resolving anything.
     */
    public boolean listsMembers(InputStream csvInput, List<CsvColumnMappingDto> columnMappings,
                                boolean skipHeaderRow, char fieldDelimiter,
                                boolean passthroughUnmapped, String memberAttribute) throws IOException {
        return hasMemberValues(parse(csvInput, skipHeaderRow, fieldDelimiter),
                CsvColumnMap.of(columnMappings, passthroughUnmapped), memberAttribute);
    }

    // ── Planning (shared by preview and import) ──────────────────────────────

    private List<RowPlan> plan(List<Map<String, String>> rows, CsvColumnMap colToAttr, String parentDn,
                               List<String> objectClasses, String memberAttribute,
                               GroupImportMembers members) {
        String newGroupMemberAttr = memberAttribute == null || memberAttribute.isBlank()
                ? "member" : memberAttribute;
        // Adding or removing members needs group.manage_members as well as
        // bulk.import; refuse the whole file up front rather than row by row.
        if (hasMemberValues(rows, colToAttr, memberAttribute)) {
            members.requireMemberChanges();
        }
        // Groups by normalized DN: what's in the directory, plus groups an
        // earlier row of this file creates (so later rows update them).
        Map<String, Optional<ExistingGroup>> groups = new HashMap<>();
        List<RowPlan> plans = new ArrayList<>(rows.size());
        int totalChanges = 0;
        int rowNum = 0;

        for (Map<String, String> row : rows) {
            rowNum++;
            RowPlan p = new RowPlan(rowNum);
            plans.add(p);

            String rawOp = null;
            List<String> memberValues = new ArrayList<>();
            if (objectClasses != null && !objectClasses.isEmpty()) {
                p.attrs.put("objectClass", objectClasses);
            }
            for (Map.Entry<String, String> cell : row.entrySet()) {
                String csvCol = cell.getKey();
                String rawVal = cell.getValue();
                if (isMemberOperationColumn(csvCol)) {
                    rawOp = rawVal;
                    continue;
                }
                if (rawVal == null || rawVal.isBlank()) continue;

                // null = ignored column, or a column the template doesn't mention;
                // without a template the header itself is the attribute name.
                String ldapAttr = colToAttr.attributeFor(csvCol);
                if (ldapAttr == null) continue;
                // The DN is built from cn + parentDn; an exported dn column must
                // not be sent as an attribute (the add would fail on every row).
                if (isDnAttribute(ldapAttr)) continue;

                p.displayAttrs.put(ldapAttr, rawVal);
                if ("members".equalsIgnoreCase(ldapAttr)
                        || (memberAttribute != null && ldapAttr.equalsIgnoreCase(memberAttribute))) {
                    for (String v : rawVal.split("\\|")) {
                        if (!v.isBlank()) memberValues.add(v.trim());
                    }
                    continue;
                }
                p.attrs.put(ldapAttr, List.of(rawVal));
            }

            List<String> cn = p.attrs.get("cn");
            if (cn != null && !cn.isEmpty()) {
                p.dn = buildDn("cn", cn.get(0), parentDn);
            }
            p.operation = parseMemberOperation(rawOp);
            if (p.operation == null) {
                p.error = "Invalid member-operation '" + rawOp.trim() + "': expected 'add' or 'delete'";
                continue;
            }
            if (rawOp != null && !rawOp.isBlank()) {
                p.displayAttrs.put(MEMBER_OPERATION_COLUMN, p.operation == Op.ADD ? "add" : "delete");
            }
            if (p.dn == null) {
                p.error = "Missing value for key attribute 'cn'";
                continue;
            }

            ExistingGroup group = groups
                    .computeIfAbsent(GroupImportMembers.normalizeDn(p.dn),
                            k -> Optional.ofNullable(members.findGroup(p.dn)))
                    .orElse(null);
            if (group != null && !group.isGroup()) {
                p.error = "The entry at " + p.dn + " is not a group";
                continue;
            }
            if (p.operation == Op.REMOVE && group == null) {
                p.error = "Group does not exist";
                continue;
            }
            p.action = group == null ? GroupAction.CREATE : GroupAction.UPDATE;
            if (group != null) {
                // Provided attributes replace the group's current values; only
                // the ones that differ are written. Blank cells never got here.
                p.attrs.forEach((attr, values) -> {
                    if (attr.equalsIgnoreCase("cn") || attr.equalsIgnoreCase("objectClass")) return;
                    List<String> current = group.valuesOf(attr);
                    if (!current.equals(values)) {
                        p.attrUpdates.add(new GroupAttributeChange(attr,
                                current.isEmpty() ? null : String.join("|", current), values.get(0)));
                    }
                });
            }
            p.memberAttribute = group == null ? newGroupMemberAttr : group.memberAttribute();
            boolean uidMembers = "memberUid".equalsIgnoreCase(p.memberAttribute);

            totalChanges += memberValues.size();
            if (totalChanges > MAX_MEMBER_CHANGES) {
                throw new IllegalArgumentException("The file has more than " + MAX_MEMBER_CHANGES
                        + " member changes; the limit is " + MAX_MEMBER_CHANGES + " per upload — split the file");
            }

            for (String value : memberValues) {
                PlannedChange c = new PlannedChange(value, p.operation, members.resolveMember(value));
                p.changes.add(c);
                if (c.member.problem() != null) {
                    c.set(c.member.problem(), c.member.note());
                } else if (uidMembers && c.member.uid() == null) {
                    c.set(Outcome.INVALID, "Group uses memberUid but this member has no uid");
                } else {
                    boolean isMember = group != null && group.hasMember(c.member);
                    if (p.operation == Op.ADD) {
                        c.set(isMember ? Outcome.ALREADY_MEMBER : Outcome.WILL_ADD,
                                isMember ? "Already a member" : null);
                    } else {
                        c.set(isMember ? Outcome.WILL_REMOVE : Outcome.NOT_A_MEMBER,
                                isMember ? null : "Not a member");
                    }
                }
            }

            if (p.action == GroupAction.CREATE) {
                Set<String> dns = new HashSet<>();
                Set<String> uids = new HashSet<>();
                for (PlannedChange c : p.changes) {
                    if (c.outcome != Outcome.WILL_ADD) continue;
                    dns.add(GroupImportMembers.normalizeDn(c.member.dn()));
                    if (c.member.uid() != null) uids.add(c.member.uid().toLowerCase(Locale.ROOT));
                }
                groups.put(GroupImportMembers.normalizeDn(p.dn),
                        Optional.of(ExistingGroup.created(p.dn, p.memberAttribute, dns, uids, p.attrs)));
            }
        }

        markDuplicatesAndConflicts(plans);
        return plans;
    }

    /** Whether any row lists members, i.e. the file asks for member changes. */
    private static boolean hasMemberValues(List<Map<String, String>> rows, CsvColumnMap colToAttr,
                                           String memberAttribute) {
        for (Map<String, String> row : rows) {
            for (Map.Entry<String, String> cell : row.entrySet()) {
                if (cell.getValue() == null || cell.getValue().isBlank()
                        || isMemberOperationColumn(cell.getKey())) continue;
                String attr = colToAttr.attributeFor(cell.getKey());
                if (attr != null && ("members".equalsIgnoreCase(attr)
                        || (memberAttribute != null && attr.equalsIgnoreCase(memberAttribute)))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The same member and group on more than one row: the same operation again
     * is a DUPLICATE (only the first counts); an add and a remove of the same
     * membership contradict each other, so none of them run.
     */
    private static void markDuplicatesAndConflicts(List<RowPlan> plans) {
        Map<String, List<PlannedChange>> byKey = new LinkedHashMap<>();
        Map<PlannedChange, Integer> rowOf = new HashMap<>();
        for (RowPlan p : plans) {
            if (p.error != null) continue;
            for (PlannedChange c : p.changes) {
                if (c.member.dn() == null) continue;
                rowOf.put(c, p.rowNumber);
                byKey.computeIfAbsent(GroupImportMembers.normalizeDn(c.member.dn()) + "\n"
                        + GroupImportMembers.normalizeDn(p.dn), k -> new ArrayList<>()).add(c);
            }
        }
        for (List<PlannedChange> same : byKey.values()) {
            if (same.size() < 2) continue;
            boolean conflicting = same.stream().map(c -> c.op).distinct().count() > 1;
            for (int i = 0; i < same.size(); i++) {
                PlannedChange c = same.get(i);
                if (conflicting) {
                    c.set(Outcome.INVALID, "The file both adds and removes this membership");
                } else if (i > 0) {
                    c.set(Outcome.DUPLICATE, "Repeats row " + rowOf.get(same.get(0)));
                }
            }
        }
    }

    // ── Execution ─────────────────────────────────────────────────────────────

    private BulkImportRowResult execute(DirectoryConnection dc, RowPlan p, GroupImportMembers members) {
        if (p.error != null) {
            return BulkImportRowResult.error(p.rowNumber, p.dn, p.error);
        }
        try {
            if (p.action == GroupAction.CREATE) {
                return create(dc, p);
            }
            String updated = null;
            if (!p.attrUpdates.isEmpty()) {
                List<Modification> mods = p.attrUpdates.stream()
                        .map(a -> new Modification(ModificationType.REPLACE, a.attribute(), a.newValue()))
                        .toList();
                groupService.updateGroup(dc, p.dn, mods);
                updated = "Updated " + String.join(", ",
                        p.attrUpdates.stream().map(GroupAttributeChange::attribute).toList());
            }
            for (PlannedChange c : p.changes) {
                if (!c.outcome.planned()) continue;
                MembershipChangeResult.Item item = members.apply(c.member, p.dn, p.memberAttribute, c.op);
                c.record(item);
            }
            return result(p, updated, false);
        } catch (Exception ex) {
            log.warn("Row {} failed [dn={}]: {}", p.rowNumber, p.dn, ex.getMessage());
            for (PlannedChange c : p.changes) {
                if (c.outcome.planned()) c.set(Outcome.ERROR, "Not attempted: " + ex.getMessage());
            }
            return new BulkImportRowResult(p.rowNumber, p.dn, BulkImportRowResult.Status.ERROR,
                    ex.getMessage(), p.changeRows());
        }
    }

    /** Creates the group with all of its resolved members in one add. */
    private BulkImportRowResult create(DirectoryConnection dc, RowPlan p) {
        Map<String, List<String>> attrMap = new LinkedHashMap<>(p.attrs);
        boolean uidMembers = "memberUid".equalsIgnoreCase(p.memberAttribute);
        List<String> values = p.changes.stream()
                .filter(c -> c.outcome == Outcome.WILL_ADD)
                .map(c -> uidMembers ? c.member.uid() : c.member.dn())
                .toList();
        if (!values.isEmpty()) {
            attrMap.put(p.memberAttribute, values);
        }
        groupService.createGroup(dc, p.dn, attrMap);
        for (PlannedChange c : p.changes) {
            if (c.outcome == Outcome.WILL_ADD) c.set(Outcome.APPLIED, null);
        }
        return result(p, null, true);
    }

    /**
     * Row status from its member outcomes: ERROR when any change failed or
     * couldn't be resolved, else CREATED / UPDATED when something was written
     * (or queued for approval), else SKIPPED.
     */
    private static BulkImportRowResult result(RowPlan p, String extra, boolean created) {
        Map<Outcome, Integer> counts = new EnumMap<>(Outcome.class);
        for (PlannedChange c : p.changes) counts.merge(c.outcome, 1, Integer::sum);

        List<String> parts = new ArrayList<>();
        if (created) parts.add("Group created");
        if (extra != null) parts.add(extra);
        String verb = p.operation == Op.ADD ? "added" : "removed";
        summarize(parts, counts, Outcome.APPLIED, verb);
        summarize(parts, counts, Outcome.QUEUED_FOR_APPROVAL, "queued for approval");
        summarize(parts, counts, Outcome.ALREADY_MEMBER, "already a member");
        summarize(parts, counts, Outcome.NOT_A_MEMBER, "not a member");
        summarize(parts, counts, Outcome.DUPLICATE, "repeated in the file");
        summarize(parts, counts, Outcome.REFUSED, "refused");
        summarize(parts, counts, Outcome.BLOCKED, "blocked by separation of duties");
        summarize(parts, counts, Outcome.ERROR, "failed");
        summarize(parts, counts, Outcome.MEMBER_NOT_FOUND, "not found");
        summarize(parts, counts, Outcome.AMBIGUOUS, "ambiguous");
        summarize(parts, counts, Outcome.OUT_OF_SCOPE, "outside your scope");
        summarize(parts, counts, Outcome.INVALID, "invalid");
        String message = parts.isEmpty() ? null : String.join("; ", parts);

        boolean failed = p.changes.stream().anyMatch(c -> c.outcome.failed());
        boolean wrote = created || extra != null
                || counts.containsKey(Outcome.APPLIED) || counts.containsKey(Outcome.QUEUED_FOR_APPROVAL);
        BulkImportRowResult.Status status = failed ? BulkImportRowResult.Status.ERROR
                : created ? BulkImportRowResult.Status.CREATED
                : wrote ? BulkImportRowResult.Status.UPDATED
                : BulkImportRowResult.Status.SKIPPED;
        if (status == BulkImportRowResult.Status.SKIPPED && message == null) {
            message = "Group already exists (no members to " + (p.operation == Op.ADD ? "add" : "remove") + ")";
        }
        return new BulkImportRowResult(p.rowNumber, p.dn, status, message, p.changeRows());
    }

    private static void summarize(List<String> parts, Map<Outcome, Integer> counts,
                                  Outcome outcome, String label) {
        Integer n = counts.get(outcome);
        if (n != null) parts.add(n + " " + label);
    }

    // ── Export ────────────────────────────────────────────────────────────────

    /**
     * Exports groups matching the given filter as CSV bytes.
     * Columns: dn, cn, description, owner, members (pipe-separated).
     */
    public byte[] exportCsv(DirectoryConnection dc,
                             String filter,
                             String baseDn,
                             String memberAttribute,
                             List<String> attributes) throws IOException {
        return exportCsvFromBases(dc, filter, Collections.singletonList(baseDn), memberAttribute, attributes);
    }

    /**
     * Multi-base variant. Used by the admin bulk-export flow when the
     * permission layer fans the request out across the admin's
     * authorized OUs. See the matching method in
     * {@link BulkUserService#exportCsvFromBases}. Across multiple
     * bases, entries are deduped by DN (case-insensitive).
     */
    public byte[] exportCsvFromBases(DirectoryConnection dc,
                             String filter,
                             List<String> baseDns,
                             String memberAttribute,
                             List<String> attributes) throws IOException {

        String effectiveFilter = (filter == null || filter.isBlank())
                ? "(|(objectClass=groupOfNames)(objectClass=groupOfUniqueNames)(objectClass=posixGroup)(objectClass=group))"
                : filter;

        List<String> bases = (baseDns == null || baseDns.isEmpty())
                ? Collections.singletonList(null) : baseDns;

        List<LdapGroup> groups = new ArrayList<>();
        Set<String> seenDns = new HashSet<>();
        for (String base : bases) {
            for (LdapGroup g : groupService.searchGroups(dc, effectiveFilter, base)) {
                if (seenDns.add(g.getDn().toLowerCase(Locale.ROOT))) {
                    groups.add(g);
                }
            }
        }

        List<String> columns = buildExportColumns(attributes);
        // Ensure the member attribute column is present
        String memberCol = memberAttribute != null ? memberAttribute : "member";
        if (!columns.contains(memberCol) && !columns.contains("members")) {
            columns.add(memberCol);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Writer writer = new OutputStreamWriter(baos, StandardCharsets.UTF_8);
        CsvUtils.writeHeader(writer, columns);

        for (LdapGroup group : groups) {
            CsvUtils.writeRow(writer, columns, buildExportRow(group, columns));
        }

        writer.flush();
        return baos.toByteArray();
    }


    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Parses the CSV, dropping the byte-order mark Excel's "CSV UTF-8" puts on the first header. */
    private static List<Map<String, String>> parse(InputStream csvInput, boolean skipHeaderRow,
                                                   char fieldDelimiter) throws IOException {
        List<Map<String, String>> rows = CsvUtils.parse(csvInput, skipHeaderRow, fieldDelimiter);
        if (rows.isEmpty() || rows.get(0).keySet().stream().noneMatch(k -> k != null && k.startsWith("﻿"))) {
            return rows;
        }
        List<Map<String, String>> cleaned = new ArrayList<>(rows.size());
        for (Map<String, String> row : rows) {
            Map<String, String> copy = new LinkedHashMap<>();
            row.forEach((k, v) -> copy.put(k != null && k.startsWith("﻿") ? k.substring(1) : k, v));
            cleaned.add(copy);
        }
        return cleaned;
    }

    /** ADD for an absent or blank value, null for anything but add / delete. */
    private static Op parseMemberOperation(String raw) {
        if (raw == null || raw.isBlank()) return Op.ADD;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "add"    -> Op.ADD;
            case "delete" -> Op.REMOVE;
            default       -> null;
        };
    }

    private static boolean isMemberOperationColumn(String csvColumn) {
        return csvColumn != null && MEMBER_OPERATION_COLUMN.equalsIgnoreCase(csvColumn.trim());
    }

    private static boolean isDnAttribute(String ldapAttr) {
        return "dn".equalsIgnoreCase(ldapAttr.trim());
    }

    private String buildDn(String rdnAttr, String rdnValue, String parentDn) {
        return new RDN(rdnAttr, rdnValue).toString() + "," + parentDn;
    }

    private List<String> buildExportColumns(List<String> requestedAttrs) {
        List<String> cols = new ArrayList<>();
        cols.add("dn");
        if (!requestedAttrs.isEmpty()) {
            cols.addAll(requestedAttrs);
        } else {
            cols.addAll(List.of("cn", "description", "owner"));
        }
        return cols;
    }

    private Map<String, String> buildExportRow(LdapGroup group, List<String> columns) {
        Map<String, String> row = new LinkedHashMap<>();
        for (String col : columns) {
            if ("dn".equals(col)) {
                row.put("dn", group.getDn());
            } else {
                List<String> vals = group.getValues(col);
                row.put(col, String.join("|", vals));
            }
        }
        return row;
    }


    private long countByStatus(List<BulkImportRowResult> results,
                                BulkImportRowResult.Status status) {
        return results.stream().filter(r -> r.status() == status).count();
    }

    // ── Plan model ────────────────────────────────────────────────────────────

    private static final class RowPlan {
        final int rowNumber;
        /** Attributes the row provides (written on create, compared on update); members excluded. */
        final Map<String, List<String>> attrs = new LinkedHashMap<>();
        /** What the preview shows for the row, members included. */
        final Map<String, String> displayAttrs = new LinkedHashMap<>();
        final List<PlannedChange> changes = new ArrayList<>();
        /** Attributes of an existing group the row gives a new value. */
        final List<GroupAttributeChange> attrUpdates = new ArrayList<>();
        String dn;
        Op operation = Op.ADD;
        GroupAction action;
        String memberAttribute;
        /** Why the row can't run at all. */
        String error;

        RowPlan(int rowNumber) {
            this.rowNumber = rowNumber;
        }

        List<GroupMemberChange> changeRows() {
            return changes.stream()
                    .map(c -> new GroupMemberChange(c.value, c.member.dn(), c.op, c.outcome, c.note, c.approvalId))
                    .toList();
        }
    }

    private static final class PlannedChange {
        final String value;
        final Op op;
        final Member member;
        Outcome outcome;
        String note;
        java.util.UUID approvalId;

        PlannedChange(String value, Op op, Member member) {
            this.value = value;
            this.op = op;
            this.member = member;
        }

        void set(Outcome o, String n) {
            this.outcome = o;
            this.note = n;
        }

        void record(MembershipChangeResult.Item item) {
            if (item == null) {
                set(Outcome.ERROR, "No result returned for this change");
                return;
            }
            Outcome o = switch (item.status()) {
                case APPLIED             -> Outcome.APPLIED;
                case QUEUED_FOR_APPROVAL -> Outcome.QUEUED_FOR_APPROVAL;
                case REFUSED             -> Outcome.REFUSED;
                case BLOCKED             -> Outcome.BLOCKED;
                case ERROR               -> Outcome.ERROR;
            };
            set(o, item.message());
            this.approvalId = item.approvalId();
        }
    }
}
