// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.dto.csv.BulkImportPreviewResult;
import com.ldapportal.dto.csv.BulkImportPreviewRow;
import com.ldapportal.dto.csv.BulkImportResult;
import com.ldapportal.dto.csv.BulkImportRowResult;
import com.ldapportal.dto.csv.CsvColumnMappingDto;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.ConflictHandling;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.exception.LdapOperationException;
import com.ldapportal.ldap.LdapUserService;
import com.ldapportal.ldap.model.LdapUser;
import com.ldapportal.util.CsvUtils;
import com.unboundid.ldap.sdk.RDN;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * CSV parsing and generation service for bulk user import and export operations.
 *
 * <h3>Import</h3>
 * <p>Reads a UTF-8 CSV stream (first row = column headers), maps each header
 * to an LDAP attribute via the supplied column mappings, then for every data
 * row either creates a new entry or updates/skips an existing one depending on
 * {@link ConflictHandling}.  All rows are processed regardless of individual
 * errors; the caller receives a per-row result list.</p>
 *
 * <h3>Export</h3>
 * <p>Searches the directory for matching entries, writes a header row followed
 * by one data row per entry, and returns the result as a UTF-8 {@code byte[]}.
 * Multi-valued LDAP attributes are serialised as pipe-separated strings.</p>
 *
 * <p>This service operates directly on {@link LdapUserService}; permission
 * checks are the caller's responsibility (see {@link LdapOperationService}).</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BulkUserService {

    private final LdapUserService userService;
    // Lazy via ObjectProvider to avoid the Spring circular-dependency check at
    // startup — ProvisioningProfileService doesn't pull BulkUserService today,
    // but threading it through other indirect paths is plausible enough that
    // the lazy lookup is cheap insurance.
    private final org.springframework.beans.factory.ObjectProvider<ProvisioningProfileService> profileServiceProvider;

    private ProvisioningProfileService profileService() {
        return profileServiceProvider.getIfAvailable();
    }

    // ── Import ────────────────────────────────────────────────────────────────

    /**
     * Backward-compatible overload — for tests and other call sites
     * that don't have profile context. Delegates with a null profile
     * context so the behaviour matches the pre-profile-aware import.
     */
    public BulkImportResult importCsv(DirectoryConnection dc,
                                      InputStream csvInput,
                                      String parentDn,
                                      String targetKeyAttr,
                                      ConflictHandling conflictHandling,
                                      List<CsvColumnMappingDto> columnMappings,
                                      List<String> objectClasses,
                                      boolean skipHeaderRow) throws IOException {
        return importCsv(dc, csvInput, parentDn, targetKeyAttr, conflictHandling,
                columnMappings, objectClasses, skipHeaderRow, null);
    }

    /**
     * Parses {@code csvInput} and applies creates/updates to the LDAP directory.
     *
     * @param dc              target directory connection
     * @param csvInput        raw CSV byte stream (UTF-8, first row = headers)
     * @param parentDn        DN of the container where new entries are created
     * @param targetKeyAttr   LDAP attribute whose value serves as the RDN (e.g. {@code uid})
     * @param conflictHandling action when an entry with the given key already exists
     * @param columnMappings  CSV column → LDAP attribute mapping;
     *                        empty list = use CSV header names as attribute names directly
     * @param profileContext  optional. When non-null, each row gets the profile's
     *                        attribute defaults applied before create (matching the
     *                        manual-create path) and the profile's effective group
     *                        assignments applied after each successful create.
     */
    public BulkImportResult importCsv(DirectoryConnection dc,
                                      InputStream csvInput,
                                      String parentDn,
                                      String targetKeyAttr,
                                      ConflictHandling conflictHandling,
                                      List<CsvColumnMappingDto> columnMappings,
                                      List<String> objectClasses,
                                      boolean skipHeaderRow,
                                      ProfileContext profileContext) throws IOException {
        if (dc.getDirectoryType() == DirectoryType.ENTRA_ID) {
            throw new IllegalArgumentException("This feature is not supported for Entra ID directories");
        }

        Map<String, String> colToAttr = resolveColumnMap(columnMappings);
        List<Map<String, String>> rows = CsvUtils.parse(csvInput, skipHeaderRow);

        List<BulkImportRowResult> rowResults = new ArrayList<>();
        int rowNum = 0;

        for (Map<String, String> row : rows) {
            rowNum++;
            rowResults.add(processRow(dc, row, colToAttr, targetKeyAttr,
                    parentDn, conflictHandling, objectClasses, rowNum, profileContext));
        }

        long created = countByStatus(rowResults, BulkImportRowResult.Status.CREATED);
        long updated = countByStatus(rowResults, BulkImportRowResult.Status.UPDATED);
        long skipped = countByStatus(rowResults, BulkImportRowResult.Status.SKIPPED);
        long errors  = countByStatus(rowResults, BulkImportRowResult.Status.ERROR);

        log.info("Bulk import complete: {} rows — created={}, updated={}, skipped={}, errors={}",
                rowNum, created, updated, skipped, errors);

        return new BulkImportResult(rowNum, created, updated, skipped, errors, rowResults);
    }

    // ── Preview ───────────────────────────────────────────────────────────────

    /**
     * Parses {@code csvInput} and builds a preview of what would be imported,
     * including computed DNs for each row. No LDAP writes are performed.
     *
     * @param requiredAttrs LDAP attribute names the row's object classes mark
     *                      as MUST. Each preview row will report any of these
     *                      that don't have a value in the row's CSV cell, so
     *                      the UI can warn the user at preview time about
     *                      schema violations that would otherwise only surface
     *                      as {@code OBJECT_CLASS_VIOLATION} during the actual
     *                      import. Pass an empty list to skip validation
     *                      (e.g. when no template is selected).
     */
    public BulkImportPreviewResult previewImport(InputStream csvInput,
                                                  String parentDn,
                                                  String targetKeyAttr,
                                                  List<CsvColumnMappingDto> columnMappings,
                                                  boolean skipHeaderRow,
                                                  List<String> requiredAttrs) throws IOException {

        Map<String, String> colToAttr = resolveColumnMap(columnMappings);
        List<Map<String, String>> rows = CsvUtils.parse(csvInput, skipHeaderRow);

        // Pre-compute lowercase required-attribute set so per-row checks are
        // case-insensitive (LDAP attribute names are case-insensitive but
        // CSV headers / template entries can carry any case).
        List<String> required = requiredAttrs == null ? List.of() : requiredAttrs;
        java.util.Set<String> requiredLower = required.stream()
                .map(s -> s.toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());

        List<BulkImportPreviewRow> previewRows = new ArrayList<>();
        int rowNum = 0;

        for (Map<String, String> row : rows) {
            rowNum++;
            Map<String, String> attrs = new LinkedHashMap<>();
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
            }

            String keyValue = attrs.get(targetKeyAttr);
            String dn = (keyValue != null && !keyValue.isBlank())
                    ? buildDn(targetKeyAttr, keyValue, parentDn)
                    : null;

            // Compare attrs (case-insensitive) against the required set.
            // Preserve the original casing of the missing attribute names so
            // the frontend can render them as the template defined them.
            java.util.Set<String> present = attrs.keySet().stream()
                    .map(s -> s.toLowerCase(java.util.Locale.ROOT))
                    .collect(java.util.stream.Collectors.toSet());
            List<String> missing = new ArrayList<>();
            // Missing RDN/key attribute is surfaced through the same channel
            // as schema MUSTs so the row gets the amber highlight + Issues
            // column + banner count. The orchestrator excludes the key attr
            // from `requiredAttrs` (it has its own dedicated error path at
            // confirm-time), but at preview time we still want it to look
            // the same as any other missing required value.
            if (dn == null) {
                missing.add(targetKeyAttr);
            }
            required.stream()
                    .filter(a -> !present.contains(a.toLowerCase(java.util.Locale.ROOT)))
                    .forEach(missing::add);

            previewRows.add(new BulkImportPreviewRow(rowNum, dn, attrs, missing));
        }

        return new BulkImportPreviewResult(rowNum, previewRows);
    }

    /** Backwards-compatible overload (no schema validation). */
    public BulkImportPreviewResult previewImport(InputStream csvInput,
                                                  String parentDn,
                                                  String targetKeyAttr,
                                                  List<CsvColumnMappingDto> columnMappings,
                                                  boolean skipHeaderRow) throws IOException {
        return previewImport(csvInput, parentDn, targetKeyAttr, columnMappings, skipHeaderRow, List.of());
    }

    // ── Export ────────────────────────────────────────────────────────────────

    /**
     * Searches the directory and serialises matching entries as CSV bytes.
     *
     * <p>Column order: {@code dn} is always the first column, followed by the
     * requested attributes in the order provided.  When {@code attributes} is
     * empty the column set is derived from all attribute names found across
     * the first page of results.</p>
     *
     * <p>Results are streamed page-by-page directly into the output buffer so
     * the entire result set never has to reside in memory simultaneously.</p>
     *
     * @param dc         directory connection
     * @param filter     LDAP filter (null = {@code (objectClass=*)})
     * @param baseDn     search base (null = directory base DN)
     * @param attributes LDAP attribute names to include as columns;
     *                   empty = derive from first page of results
     */
    public byte[] exportCsv(DirectoryConnection dc,
                             String filter,
                             String baseDn,
                             List<String> attributes) throws IOException {
        return exportCsvFromBases(dc, filter, Collections.singletonList(baseDn), attributes);
    }

    /**
     * Multi-base variant. Used by the admin bulk-export flow when the
     * permission layer fans the request out across the admin's
     * authorized OUs (no explicit baseDn, or an explicit ancestor
     * baseDn that has to be clamped). The list may contain a single
     * null entry to mean "search at the directory base DN" — that's
     * the superadmin / unscoped path.
     *
     * <p>Across multiple bases, entries are deduped by DN
     * (case-insensitive) so an admin whose authorized OUs overlap
     * doesn't get duplicate rows in the CSV.</p>
     */
    public byte[] exportCsvFromBases(DirectoryConnection dc,
                             String filter,
                             List<String> baseDns,
                             List<String> attributes) throws IOException {
        if (dc.getDirectoryType() == DirectoryType.ENTRA_ID) {
            throw new IllegalArgumentException("This feature is not supported for Entra ID directories");
        }

        String effectiveFilter = (filter == null || filter.isBlank())
                ? "(objectClass=*)" : filter;
        String[] attrArray = attributes.isEmpty()
                ? new String[0]
                : attributes.toArray(new String[0]);

        List<String> bases = (baseDns == null || baseDns.isEmpty())
                ? Collections.singletonList(null) : baseDns;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Writer writer = new OutputStreamWriter(baos, StandardCharsets.UTF_8);
        Set<String> seenDns = new HashSet<>();

        // When columns are known upfront (attributes provided), write header first and
        // stream entries via processUsers — only one entry lives in memory at a time.
        // When attributes is empty, columns are derived dynamically; we must do a full
        // search to discover the union of all attribute names before writing the header.
        if (!attributes.isEmpty()) {
            List<String> columns = buildExportColumns(List.of(), attributes);
            CsvUtils.writeHeader(writer, columns);
            for (String base : bases) {
                userService.processUsers(dc, effectiveFilter, base,
                        user -> {
                            if (!seenDns.add(user.getDn().toLowerCase(Locale.ROOT))) return;
                            try {
                                CsvUtils.writeRow(writer, columns, buildExportRow(user, columns));
                            } catch (IOException e) {
                                throw new RuntimeException("CSV write failed", e);
                            }
                        },
                        attrArray);
            }
        } else {
            // Fallback: load all entries (across all bases) to determine the dynamic
            // column set, then write. Dedupe across bases by DN.
            List<LdapUser> users = new ArrayList<>();
            for (String base : bases) {
                for (LdapUser u : userService.searchUsers(dc, effectiveFilter, base, attrArray)) {
                    if (seenDns.add(u.getDn().toLowerCase(Locale.ROOT))) {
                        users.add(u);
                    }
                }
            }
            List<String> columns = buildExportColumns(users, attributes);
            CsvUtils.writeHeader(writer, columns);
            for (LdapUser user : users) {
                CsvUtils.writeRow(writer, columns, buildExportRow(user, columns));
            }
        }

        writer.flush();
        return baos.toByteArray();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Processes a single CSV data row: builds the LDAP attribute map, checks
     * whether the target entry already exists, and applies the appropriate
     * create/update/skip action.
     */
    private BulkImportRowResult processRow(DirectoryConnection dc,
                                           Map<String, String> row,
                                           Map<String, String> colToAttr,
                                           String targetKeyAttr,
                                           String parentDn,
                                           ConflictHandling conflictHandling,
                                           List<String> objectClasses,
                                           int rowNum,
                                           ProfileContext profileContext) {
        // Build ldapAttribute→[value] map from the CSV row
        Map<String, List<String>> attrMap = new LinkedHashMap<>();

        // Inject objectClass values from the template
        if (objectClasses != null && !objectClasses.isEmpty()) {
            attrMap.put("objectClass", objectClasses);
        }

        for (Map.Entry<String, String> cell : row.entrySet()) {
            String csvCol  = cell.getKey();
            String rawVal  = cell.getValue();
            if (rawVal == null || rawVal.isBlank()) continue;

            // colToAttr: null value = explicitly ignored; absent key = passthrough
            String ldapAttr;
            if (colToAttr.containsKey(csvCol)) {
                ldapAttr = colToAttr.get(csvCol);
                if (ldapAttr == null) continue; // ignored column
            } else {
                ldapAttr = csvCol; // passthrough: header IS the attribute name
            }

            attrMap.put(ldapAttr, List.of(rawVal));
        }

        // Apply profile attribute defaults BEFORE the create, matching
        // UserController.create's behaviour: any computed / default /
        // fixed value the profile declares is populated for fields the
        // CSV didn't provide. CSV-supplied values win over defaults
        // because the CSV cell wrote to attrMap above.
        if (profileContext != null && profileContext.profileId() != null) {
            ProvisioningProfileService ps = profileService();
            if (ps != null) {
                // applyDefaults mutates the map: it fills in missing
                // computed/default/fixed values without overwriting
                // anything already present.
                Map<String, List<String>> mutable = new LinkedHashMap<>(attrMap);
                ps.applyDefaults(profileContext.profileId(), mutable);
                attrMap = mutable;
            }
        }

        // The key attribute value drives both DN construction and duplicate detection
        List<String> keyValues = attrMap.get(targetKeyAttr);
        if (keyValues == null || keyValues.isEmpty()) {
            return BulkImportRowResult.error(rowNum, null,
                    "Missing value for key attribute '" + targetKeyAttr + "'");
        }
        String keyValue = keyValues.get(0);
        String dn = buildDn(targetKeyAttr, keyValue, parentDn);

        try {
            // Optimistic create: attempt the add first and handle the ENTRY_ALREADY_EXISTS
            // result code inline.  This avoids a separate getUser() existence check per row
            // (which would double the number of LDAP round-trips for large imports).
            try {
                userService.createUser(dc, dn, attrMap);
                // Group assignments after a successful create, mirroring
                // the manual create path. Skipped on UPDATE / SKIP / ERROR
                // because the user existed already (and may not belong to
                // this profile).
                applyProfileGroups(dn, profileContext);
                return BulkImportRowResult.created(rowNum, dn);
            } catch (LdapOperationException ex) {
                if (!ex.getMessage().contains(ResultCode.ENTRY_ALREADY_EXISTS.getName())
                        && !ex.getMessage().contains(String.valueOf(ResultCode.ENTRY_ALREADY_EXISTS.intValue()))) {
                    throw ex; // not a duplicate — propagate
                }
                // Entry exists — apply conflict strategy
                if (conflictHandling == ConflictHandling.OVERWRITE) {
                    List<Modification> mods = attrMap.entrySet().stream()
                            .filter(e -> !e.getKey().equals(targetKeyAttr))
                            .map(e -> new Modification(
                                    ModificationType.REPLACE,
                                    e.getKey(),
                                    e.getValue().toArray(new String[0])))
                            .toList();
                    if (!mods.isEmpty()) {
                        userService.updateUser(dc, dn, mods);
                    }
                    return BulkImportRowResult.updated(rowNum, dn);
                } else {
                    // SKIP or PROMPT — no action taken
                    return BulkImportRowResult.skipped(rowNum, dn, "Entry already exists");
                }
            }
        } catch (Exception ex) {
            log.warn("Row {} failed [dn={}]: {}", rowNum, dn, ex.getMessage());
            return BulkImportRowResult.error(rowNum, dn, ex.getMessage());
        }
    }

    private void applyProfileGroups(String userDn, ProfileContext context) {
        if (context == null || context.profileId() == null) return;
        ProvisioningProfileService ps = profileService();
        if (ps == null) return;
        try {
            ps.applyGroupAssignmentsToUser(
                    context.directoryId(), context.profileId(), userDn, context.principal());
        } catch (Exception e) {
            // Don't fail the import for a group-assignment hiccup —
            // the user entry exists, log + continue. Matches the
            // per-group failure behaviour inside applyGroupChanges.
            log.warn("Bulk import: profile group assignment failed for {}: {}",
                    userDn, e.getMessage());
        }
    }

    /**
     * Profile-aware context for a bulk import. Carrying directoryId +
     * profileId + principal lets {@link #processRow} apply attribute
     * defaults and group assignments per row without each row needing
     * its own profile lookup (the profile is fixed at the parent DN).
     */
    public record ProfileContext(
            java.util.UUID directoryId,
            java.util.UUID profileId,
            com.ldapportal.auth.AuthPrincipal principal) {}

    /**
     * Builds a lookup map from CSV column name to LDAP attribute name.
     * A {@code null} value in the returned map signals "ignore this column".
     * Columns absent from the map are handled as passthrough.
     */
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

    /**
     * Constructs the full DN for a new entry.
     * The RDN value is escaped per RFC 4514 so that special characters
     * (comma, equals, plus, less-than, greater-than, hash, semicolon,
     * backslash, double-quote, NUL) cannot corrupt or inject into the DN.
     */
    private String buildDn(String rdnAttr, String rdnValue, String parentDn) {
        return new RDN(rdnAttr, rdnValue).toString() + "," + parentDn;
    }

    /**
     * Determines the ordered column list for the export CSV.
     * {@code dn} is always first; remaining columns come from {@code requestedAttrs}
     * or, if empty, from all attribute names found in the returned entries.
     */
    private List<String> buildExportColumns(List<LdapUser> users, List<String> requestedAttrs) {
        List<String> cols = new ArrayList<>();
        cols.add("dn");
        if (!requestedAttrs.isEmpty()) {
            cols.addAll(requestedAttrs);
        } else {
            users.stream()
                    .flatMap(u -> u.getAttributes().keySet().stream())
                    .distinct()
                    .forEach(cols::add);
        }
        return cols;
    }

    /** Builds a single CSV data row from an LDAP entry. Multi-values are pipe-joined. */
    private Map<String, String> buildExportRow(LdapUser user, List<String> columns) {
        Map<String, String> row = new LinkedHashMap<>();
        for (String col : columns) {
            if ("dn".equals(col)) {
                row.put("dn", user.getDn());
            } else {
                List<String> vals = user.getValues(col);
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
