// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.superadmin;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.dto.ldap.AttributeModification;
import com.ldapportal.dto.ldap.CreateEntryRequest;
import com.ldapportal.dto.ldap.IntegrityReport;
import com.ldapportal.dto.ldap.IntegrityReport.IssueType;
import com.ldapportal.dto.ldap.LdifImportResult;
import com.ldapportal.dto.ldap.MoveEntryRequest;
import com.ldapportal.dto.ldap.RenameEntryRequest;
import com.ldapportal.dto.ldap.UpdateEntryRequest;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.ConflictHandling;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.ldap.IntegrityCheckService;
import com.ldapportal.ldap.LdapBrowseService;
import com.ldapportal.ldap.LdapBrowseService.BrowseResult;
import com.ldapportal.ldap.LdapBrowseService.SearchEntry;
import com.ldapportal.ldap.LdapSchemaService;
import com.ldapportal.ldap.LdapSchemaService.ObjectClassAttributes;
import com.ldapportal.ldap.LdifService;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.service.AuditService;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.unboundid.ldap.sdk.SearchScope;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Superadmin-only DIT browser — lists children of a DN, returns
 * the entry's attributes, and supports creating new entries.
 *
 * <pre>
 *   GET  /api/v1/superadmin/directories/{directoryId}/browse?dn=...
 *   POST /api/v1/superadmin/directories/{directoryId}/browse
 *   GET  /api/v1/superadmin/directories/{directoryId}/browse/schema/object-classes
 *   GET  /api/v1/superadmin/directories/{directoryId}/browse/schema/object-classes/bulk?names=...
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/superadmin/directories/{directoryId}/browse")
@PreAuthorize("hasRole('SUPERADMIN')")
@RequiredArgsConstructor
public class BrowseController {

    private final LdapBrowseService browseService;
    private final LdapSchemaService schemaService;
    private final LdifService ldifService;
    private final IntegrityCheckService integrityCheckService;
    private final AuditService auditService;
    private final DirectoryConnectionRepository dirRepo;

    @GetMapping
    public BrowseResult browse(@PathVariable UUID directoryId,
                               @RequestParam(required = false) String dn) {
        DirectoryConnection dc = loadDirectory(directoryId);
        return browseService.browse(dc, dn);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BrowseResult createEntry(@PathVariable UUID directoryId,
                                    @AuthenticationPrincipal AuthPrincipal principal,
                                    @Valid @RequestBody CreateEntryRequest req) {
        DirectoryConnection dc = loadDirectory(directoryId);
        browseService.createEntry(dc, req.dn(), req.attributes());

        // Audit the creation
        auditService.record(principal, directoryId, AuditAction.ENTRY_CREATE, req.dn(),
                Map.of("attributes", req.attributes().keySet()));

        // Return the parent's browse result so the UI can refresh the tree
        String parentDn = extractParentDn(req.dn(), dc.getBaseDn());
        return browseService.browse(dc, parentDn);
    }

    @PutMapping
    public BrowseResult updateEntry(@PathVariable UUID directoryId,
                                    @AuthenticationPrincipal AuthPrincipal principal,
                                    @RequestParam String dn,
                                    @Valid @RequestBody UpdateEntryRequest req) {
        DirectoryConnection dc = loadDirectory(directoryId);

        List<Modification> mods = req.modifications().stream()
                .map(m -> new Modification(
                        toModificationType(m.operation()),
                        m.attribute(),
                        m.values() != null ? m.values().toArray(new String[0]) : new String[0]))
                .toList();

        browseService.updateEntry(dc, dn, mods);

        auditService.record(principal, directoryId, AuditAction.ENTRY_UPDATE, dn,
                Map.of("modifications", req.modifications().size()));

        return browseService.browse(dc, dn);
    }

    @DeleteMapping
    public BrowseResult deleteEntry(@PathVariable UUID directoryId,
                                    @AuthenticationPrincipal AuthPrincipal principal,
                                    @RequestParam String dn,
                                    @RequestParam(defaultValue = "false") boolean recursive) {
        DirectoryConnection dc = loadDirectory(directoryId);
        String parentDn = extractParentDn(dn, dc.getBaseDn());

        browseService.deleteEntry(dc, dn, recursive);

        auditService.record(principal, directoryId, AuditAction.ENTRY_DELETE, dn,
                Map.of("recursive", recursive));

        return browseService.browse(dc, parentDn);
    }

    @PostMapping("/move")
    public BrowseResult moveEntry(@PathVariable UUID directoryId,
                                  @AuthenticationPrincipal AuthPrincipal principal,
                                  @RequestParam String dn,
                                  @Valid @RequestBody MoveEntryRequest req) {
        DirectoryConnection dc = loadDirectory(directoryId);
        browseService.moveEntry(dc, dn, req.newParentDn());

        auditService.record(principal, directoryId, AuditAction.ENTRY_MOVE, dn,
                Map.of("newParentDn", req.newParentDn()));

        return browseService.browse(dc, req.newParentDn());
    }

    @PostMapping("/rename")
    public BrowseResult renameEntry(@PathVariable UUID directoryId,
                                    @AuthenticationPrincipal AuthPrincipal principal,
                                    @RequestParam String dn,
                                    @Valid @RequestBody RenameEntryRequest req) {
        DirectoryConnection dc = loadDirectory(directoryId);
        browseService.renameEntry(dc, dn, req.newRdn());

        String parentDn = extractParentDn(dn, dc.getBaseDn());
        auditService.record(principal, directoryId, AuditAction.ENTRY_RENAME, dn,
                Map.of("newRdn", req.newRdn()));

        return browseService.browse(dc, parentDn);
    }

    private ModificationType toModificationType(AttributeModification.Operation op) {
        return switch (op) {
            case ADD -> ModificationType.ADD;
            case REPLACE -> ModificationType.REPLACE;
            case DELETE -> ModificationType.DELETE;
        };
    }

    // ── Search ────────────────────────────────────────────────────────────────

    @GetMapping("/search")
    public List<SearchEntry> searchEntries(
            @PathVariable UUID directoryId,
            @RequestParam(required = false) String baseDn,
            @RequestParam(defaultValue = "sub") String scope,
            @RequestParam(required = false) String filter,
            @RequestParam(required = false) String attributes,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int timeLimit,
            @RequestParam(defaultValue = "false") boolean includeOperational) {
        DirectoryConnection dc = loadDirectory(directoryId);

        SearchScope searchScope = switch (scope.toLowerCase()) {
            case "base" -> SearchScope.BASE;
            case "one"  -> SearchScope.ONE;
            default     -> SearchScope.SUB;
        };

        int safeLimit = Math.max(1, Math.min(limit, 1000));
        // Time limit: clamp to [0, 3600]. 0 means "no server-side limit"
        // (matches LDAP's standard semantic). Cap at 1 hour so a user
        // can't pin a connection forever via a single query.
        int safeTimeLimit = Math.max(0, Math.min(timeLimit, 3600));
        // Split + trim attribute names. The user-facing Attributes input
        // accepts "cn, mail, uid" (with spaces) — without trimming, " mail"
        // would be sent as a literal attribute name and the server would
        // silently return nothing for it. Empty entries are filtered so a
        // trailing comma doesn't become an empty attr request.
        List<String> attrList = (attributes == null || attributes.isBlank())
                ? List.of()
                : Arrays.stream(attributes.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();

        return browseService.searchEntries(
                dc, baseDn, searchScope, filter, attrList, safeLimit,
                safeTimeLimit, includeOperational);
    }

    // ── LDIF Export ────────────────────────────────────────────────────────────

    @GetMapping("/export/ldif")
    public void exportLdif(@PathVariable UUID directoryId,
                           @RequestParam String dn,
                           @RequestParam(defaultValue = "base") String scope,
                           HttpServletResponse response) throws IOException {
        DirectoryConnection dc = loadDirectory(directoryId);

        SearchScope searchScope = switch (scope.toLowerCase()) {
            case "one" -> SearchScope.ONE;
            case "sub" -> SearchScope.SUB;
            default    -> SearchScope.BASE;
        };

        response.setContentType("application/ldif");
        response.setHeader("Content-Disposition", "attachment; filename=\"export.ldif\"");

        if (searchScope == SearchScope.BASE) {
            ldifService.exportEntry(dc, dn, response.getOutputStream());
        } else {
            ldifService.exportSubtree(dc, dn, searchScope, response.getOutputStream());
        }
    }

    // ── LDIF Import ─────────────────────────────────────────────────────────

    @PostMapping("/import/ldif")
    public LdifImportResult importLdif(@PathVariable UUID directoryId,
                                       @AuthenticationPrincipal AuthPrincipal principal,
                                       @RequestParam("file") MultipartFile file,
                                       @RequestParam(defaultValue = "SKIP") ConflictHandling conflictHandling,
                                       @RequestParam(defaultValue = "false") boolean dryRun) throws IOException {
        DirectoryConnection dc = loadDirectory(directoryId);

        LdifImportResult result = ldifService.importLdif(
                dc, file.getInputStream(), conflictHandling, dryRun);

        auditService.record(principal, directoryId, AuditAction.LDIF_IMPORT, dc.getBaseDn(),
                Map.of("added", result.added(),
                       "updated", result.updated(),
                       "skipped", result.skipped(),
                       "failed", result.failed(),
                       "dryRun", dryRun));

        return result;
    }

    // ── Integrity Check ─────────────────────────────────────────────────────

    @PostMapping("/integrity-check")
    public IntegrityReport integrityCheck(
            @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) String baseDn,
            @RequestParam(defaultValue = "BROKEN_MEMBER,ORPHANED_ENTRY,EMPTY_GROUP") String checks) {
        DirectoryConnection dc = loadDirectory(directoryId);

        Set<IssueType> checkTypes = Arrays.stream(checks.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(IssueType::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(IssueType.class)));

        IntegrityReport report = integrityCheckService.runChecks(dc, baseDn, checkTypes);

        auditService.record(principal, directoryId, AuditAction.INTEGRITY_CHECK,
                baseDn != null ? baseDn : dc.getBaseDn(),
                Map.of("checks", checks, "issuesFound", report.issues().size()));

        return report;
    }

    // ── Schema endpoints (superadmin bypass — no realm/feature checks) ────────

    @GetMapping("/schema/object-classes")
    public List<String> listObjectClasses(@PathVariable UUID directoryId) {
        DirectoryConnection dc = loadDirectory(directoryId);
        return schemaService.getObjectClassNames(dc).stream()
            .map(LdapSchemaService.SchemaListItem::name)
            .collect(java.util.stream.Collectors.toList());
    }

    @GetMapping("/schema/object-classes/bulk")
    public ObjectClassAttributes getObjectClassesBulk(
            @PathVariable UUID directoryId,
            @RequestParam List<String> names) {
        DirectoryConnection dc = loadDirectory(directoryId);
        return schemaService.getAttributesForObjectClasses(dc, names);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private DirectoryConnection loadDirectory(UUID directoryId) {
        return dirRepo.findById(directoryId)
                .orElseThrow(() -> new ResourceNotFoundException("DirectoryConnection", directoryId));
    }

    /**
     * Extracts the parent DN from a full DN string.
     * e.g. "ou=People,dc=example,dc=com" → "dc=example,dc=com"
     */
    private String extractParentDn(String dn, String baseDn) {
        int idx = dn.indexOf(',');
        if (idx < 0 || idx + 1 >= dn.length()) {
            return baseDn;
        }
        return dn.substring(idx + 1);
    }
}
