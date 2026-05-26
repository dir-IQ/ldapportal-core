// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.dto.csv.BulkImportPreviewResult;
import com.ldapportal.dto.csv.BulkImportPreviewRow;
import com.ldapportal.dto.csv.BulkImportResult;
import com.ldapportal.dto.csv.BulkImportRowResult;
import com.ldapportal.dto.csv.CsvColumnMappingDto;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.ConflictHandling;
import com.ldapportal.exception.LdapOperationException;
import com.ldapportal.ldap.LdapGroupService;
import com.ldapportal.ldap.model.LdapGroup;
import com.ldapportal.util.CsvUtils;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.unboundid.ldap.sdk.RDN;
import com.unboundid.ldap.sdk.ResultCode;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * CSV parsing and generation service for bulk group import and export operations.
 *
 * <p>Follows the same patterns as {@link BulkUserService}: optimistic creates,
 * per-row error tracking, pipe-separated multi-valued attributes, and RFC 4180
 * compliant CSV via {@link CsvUtils}.</p>
 *
 * <p>The {@code members} column (if present) is treated specially: pipe-separated
 * values are added to the configured member attribute after the group is created.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BulkGroupService {

    private final LdapGroupService groupService;

    // ── Import ────────────────────────────────────────────────────────────────

    /**
     * Parses {@code csvInput} and creates/updates groups in the LDAP directory.
     *
     * @param dc               target directory connection
     * @param csvInput         raw CSV byte stream (UTF-8)
     * @param parentDn         DN of the container where new groups are created
     * @param conflictHandling action when a group already exists
     * @param columnMappings   CSV column → LDAP attribute mappings
     * @param objectClasses    objectClass values for new groups
     * @param memberAttribute  LDAP attribute for membership (e.g. member, uniqueMember, memberUid)
     * @param skipHeaderRow    whether the first row is a header
     */
    public BulkImportResult importCsv(DirectoryConnection dc,
                                       InputStream csvInput,
                                       String parentDn,
                                       ConflictHandling conflictHandling,
                                       List<CsvColumnMappingDto> columnMappings,
                                       List<String> objectClasses,
                                       String memberAttribute,
                                       boolean skipHeaderRow) throws IOException {

        Map<String, String> colToAttr = resolveColumnMap(columnMappings);
        List<Map<String, String>> rows = CsvUtils.parse(csvInput, skipHeaderRow);

        List<BulkImportRowResult> rowResults = new ArrayList<>();
        int rowNum = 0;

        for (Map<String, String> row : rows) {
            rowNum++;
            rowResults.add(processRow(dc, row, colToAttr, parentDn,
                    conflictHandling, objectClasses, memberAttribute, rowNum));
        }

        long created = countByStatus(rowResults, BulkImportRowResult.Status.CREATED);
        long updated = countByStatus(rowResults, BulkImportRowResult.Status.UPDATED);
        long skipped = countByStatus(rowResults, BulkImportRowResult.Status.SKIPPED);
        long errors  = countByStatus(rowResults, BulkImportRowResult.Status.ERROR);

        log.info("Bulk group import complete: {} rows — created={}, updated={}, skipped={}, errors={}",
                rowNum, created, updated, skipped, errors);

        return new BulkImportResult(rowNum, created, updated, skipped, errors, rowResults);
    }

    // ── Preview ───────────────────────────────────────────────────────────────

    /**
     * Builds a preview of a group bulk import.
     *
     * @param requiredAttrs LDAP attribute names the chosen group object class
     *                      marks as MUST. Per row, anything from this list
     *                      that doesn't have a value in the CSV cell is
     *                      reported as missing. Empty list = no validation.
     * @param memberAttr    The LDAP attribute the {@code members} CSV column
     *                      maps to at import time (member / uniqueMember /
     *                      memberUid). Used so the required-attr check sees a
     *                      row's members value as supplying that attribute,
     *                      not as supplying the literal {@code "members"}
     *                      attribute (which doesn't exist in any standard
     *                      schema).
     */
    public BulkImportPreviewResult previewImport(InputStream csvInput,
                                                  String parentDn,
                                                  List<CsvColumnMappingDto> columnMappings,
                                                  boolean skipHeaderRow,
                                                  List<String> requiredAttrs,
                                                  String memberAttr) throws IOException {

        Map<String, String> colToAttr = resolveColumnMap(columnMappings);
        List<Map<String, String>> rows = CsvUtils.parse(csvInput, skipHeaderRow);

        List<String> required = requiredAttrs == null ? List.of() : requiredAttrs;
        java.util.Locale ROOT = java.util.Locale.ROOT;
        String memberAttrLower = memberAttr == null ? null : memberAttr.toLowerCase(ROOT);

        List<BulkImportPreviewRow> previewRows = new ArrayList<>();
        int rowNum = 0;

        for (Map<String, String> row : rows) {
            rowNum++;
            Map<String, String> attrs = new LinkedHashMap<>();
            // Track which LDAP attributes the row supplies for the required-
            // attr check. Diverges from the displayed `attrs` map so that the
            // CSV's `members` column registers as supplying memberAttr (e.g.
            // `member` for groupOfNames) rather than the literal `members`.
            java.util.Set<String> presentLower = new java.util.HashSet<>();
            for (Map.Entry<String, String> cell : row.entrySet()) {
                String csvCol = cell.getKey();
                String rawVal = cell.getValue();
                if (rawVal == null || rawVal.isBlank()) continue;

                String ldapAttr;
                if (colToAttr.containsKey(csvCol)) {
                    ldapAttr = colToAttr.get(csvCol);
                    if (ldapAttr == null) continue;
                } else {
                    ldapAttr = csvCol;
                }
                attrs.put(ldapAttr, rawVal);
                presentLower.add(ldapAttr.toLowerCase(ROOT));
                // The 'members' column gets translated to the chosen
                // memberAttr (member / uniqueMember / memberUid) at import.
                if (memberAttrLower != null && "members".equalsIgnoreCase(ldapAttr)) {
                    presentLower.add(memberAttrLower);
                }
            }

            String cnValue = attrs.get("cn");
            String dn = (cnValue != null && !cnValue.isBlank())
                    ? buildDn("cn", cnValue, parentDn)
                    : null;

            List<String> missing = new ArrayList<>();
            // Surface a missing cn (the group RDN) the same way as schema
            // MUSTs so the row gets the amber highlight + Issues column +
            // banner count. The orchestrator filters cn out of
            // `requiredAttrs` (cn is special-cased on the import side), but
            // at preview time we still want it to look like any other
            // missing required value.
            if (dn == null) {
                missing.add("cn");
            }
            required.stream()
                    .filter(a -> !presentLower.contains(a.toLowerCase(ROOT)))
                    .forEach(missing::add);

            previewRows.add(new BulkImportPreviewRow(rowNum, dn, attrs, missing));
        }

        return new BulkImportPreviewResult(rowNum, previewRows);
    }

    /** Backwards-compatible overload (no schema validation). */
    public BulkImportPreviewResult previewImport(InputStream csvInput,
                                                  String parentDn,
                                                  List<CsvColumnMappingDto> columnMappings,
                                                  boolean skipHeaderRow) throws IOException {
        return previewImport(csvInput, parentDn, columnMappings, skipHeaderRow, List.of(), null);
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

    // ── Private helpers ───────────────────────────────────────────────────────

    private BulkImportRowResult processRow(DirectoryConnection dc,
                                           Map<String, String> row,
                                           Map<String, String> colToAttr,
                                           String parentDn,
                                           ConflictHandling conflictHandling,
                                           List<String> objectClasses,
                                           String memberAttribute,
                                           int rowNum) {
        Map<String, List<String>> attrMap = new LinkedHashMap<>();

        if (objectClasses != null && !objectClasses.isEmpty()) {
            attrMap.put("objectClass", objectClasses);
        }

        // Track members separately (pipe-separated in CSV)
        List<String> members = new ArrayList<>();

        for (Map.Entry<String, String> cell : row.entrySet()) {
            String csvCol  = cell.getKey();
            String rawVal  = cell.getValue();
            if (rawVal == null || rawVal.isBlank()) continue;

            String ldapAttr;
            if (colToAttr.containsKey(csvCol)) {
                ldapAttr = colToAttr.get(csvCol);
                if (ldapAttr == null) continue; // ignored column
            } else {
                ldapAttr = csvCol;
            }

            // Collect members from the configured member attribute or "members" alias
            if ("members".equalsIgnoreCase(ldapAttr)
                    || ldapAttr.equalsIgnoreCase(memberAttribute)) {
                members.addAll(Arrays.asList(rawVal.split("\\|")));
                continue;
            }

            attrMap.put(ldapAttr, List.of(rawVal));
        }

        List<String> cnValues = attrMap.get("cn");
        if (cnValues == null || cnValues.isEmpty()) {
            return BulkImportRowResult.error(rowNum, null,
                    "Missing value for key attribute 'cn'");
        }
        String cnValue = cnValues.get(0);
        String dn = buildDn("cn", cnValue, parentDn);

        try {
            try {
                // For groupOfNames/groupOfUniqueNames, LDAP requires at least one member
                // on creation. Add a placeholder if needed and members are provided.
                if (!members.isEmpty() && !"memberUid".equalsIgnoreCase(memberAttribute)) {
                    attrMap.put(memberAttribute, List.of(members.get(0)));
                }
                groupService.createGroup(dc, dn, attrMap);

                // Add remaining members after creation
                for (int i = 1; i < members.size(); i++) {
                    try {
                        groupService.addMember(dc, dn, memberAttribute, members.get(i));
                    } catch (Exception e) {
                        log.warn("Row {} — failed to add member {} to {}: {}", rowNum, members.get(i), dn, e.getMessage());
                    }
                }
                return BulkImportRowResult.created(rowNum, dn);
            } catch (LdapOperationException ex) {
                if (!ex.getMessage().contains(ResultCode.ENTRY_ALREADY_EXISTS.getName())
                        && !ex.getMessage().contains(String.valueOf(ResultCode.ENTRY_ALREADY_EXISTS.intValue()))) {
                    throw ex;
                }
                if (conflictHandling == ConflictHandling.OVERWRITE) {
                    List<Modification> mods = attrMap.entrySet().stream()
                            .filter(e -> !e.getKey().equals("cn") && !e.getKey().equals("objectClass"))
                            .map(e -> new Modification(
                                    ModificationType.REPLACE,
                                    e.getKey(),
                                    e.getValue().toArray(new String[0])))
                            .toList();
                    if (!mods.isEmpty()) {
                        groupService.updateGroup(dc, dn, mods);
                    }
                    // Re-add members on overwrite
                    for (String member : members) {
                        try {
                            groupService.addMember(dc, dn, memberAttribute, member);
                        } catch (Exception e) {
                            // Member may already exist — ignore
                        }
                    }
                    return BulkImportRowResult.updated(rowNum, dn);
                } else {
                    return BulkImportRowResult.skipped(rowNum, dn, "Entry already exists");
                }
            }
        } catch (Exception ex) {
            log.warn("Row {} failed [dn={}]: {}", rowNum, dn, ex.getMessage());
            return BulkImportRowResult.error(rowNum, dn, ex.getMessage());
        }
    }

    private Map<String, String> resolveColumnMap(List<CsvColumnMappingDto> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (CsvColumnMappingDto m : mappings) {
            if (m.ignored()) {
                result.put(m.csvColumn(), null);
            } else {
                result.put(m.csvColumn(),
                        m.ldapAttribute() != null ? m.ldapAttribute() : m.csvColumn());
            }
        }
        return result;
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
}
