// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.core.governance.MembershipGate;
import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PermissionService;
import com.ldapportal.dto.csv.BulkImportPreviewResult;
import com.ldapportal.dto.csv.BulkImportRequest;
import com.ldapportal.dto.csv.BulkImportResult;
import com.ldapportal.dto.csv.CsvColumnMappingDto;
import com.ldapportal.dto.ldap.AttributeModification;
import com.ldapportal.dto.ldap.BulkAttributeUpdateRequest;
import com.ldapportal.dto.ldap.BulkAttributeUpdateResult;
import com.ldapportal.dto.ldap.CreateEntryRequest;
import com.ldapportal.dto.ldap.LdapEntryResponse;
import com.ldapportal.dto.ldap.MoveUserRequest;
import com.ldapportal.dto.ldap.UpdateEntryRequest;
import com.ldapportal.entity.CsvMappingTemplate;
import com.ldapportal.entity.CsvMappingTemplateEntry;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.ConflictHandling;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.ldap.LdapBrowseService;
import com.ldapportal.ldap.LdapBrowseService.BrowseResult;
import com.ldapportal.ldap.LdapGroupService;
import com.ldapportal.ldap.LdapSchemaService;
import com.ldapportal.ldap.LdapSchemaService.AttributeTypeInfo;
import com.ldapportal.ldap.LdapSchemaService.ObjectClassAttributes;
import com.ldapportal.ldap.LdapSchemaService.SchemaListItem;
import com.ldapportal.ldap.LdapUserService;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Permission-checked façade over the raw LDAP services.
 *
 * <p>This service is the single entry-point for all LDAP directory operations
 * in the REST layer.  Each method:</p>
 * <ol>
 *   <li>Loads the {@link DirectoryConnection} and verifies it is enabled.</li>
 *   <li>Enforces branch access (dimension 3) for entry-level operations.</li>
 *   <li>Delegates to the underlying LDAP service.</li>
 *   <li>Fires an async audit event via {@link AuditService} for write ops.</li>
 * </ol>
 *
 * <p>Feature permission checks (dimensions 1, 2, 4) are enforced by the
 * {@link com.ldapportal.auth.FeaturePermissionAspect} via
 * {@link com.ldapportal.auth.RequiresFeature} annotations on the calling
 * controller methods. Directory-access checks for read-only operations
 * (which carry no feature annotation) are performed here directly.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LdapOperationService {

    private final DirectoryConnectionRepository dirRepo;
    private final PermissionService             permissionService;
    private final LdapBrowseService             browseService;
    private final LdapUserService               userService;
    private final LdapGroupService              groupService;
    private final LdapSchemaService             schemaService;
    private final AuditService                  auditService;
    private final BulkUserService               bulkUserService;
    private final BulkGroupService              bulkGroupService;
    private final CsvMappingTemplateService     csvTemplateService;
    private final MembershipGate                membershipGate;
    // Lazy: ProvisioningProfileService isn't on the hot path of this
    // service, and lazy resolution keeps the constructor graph
    // unchanged if the service is ever swapped for a stub in tests.
    private final org.springframework.beans.factory.ObjectProvider<ProvisioningProfileService> profileServiceProvider;

    // ── Browse ────────────────────────────────────────────────────────────────

    public BrowseResult browse(UUID directoryId, AuthPrincipal principal, String dn) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDirectoryAccess(principal, directoryId);
        permissionService.requireBaseDnWithinScope(principal, directoryId, dn);
        return browseService.browse(dc, dn);
    }

    /**
     * Returns whether an entry exists at {@code dn}. Used by bulk-import
     * flows to validate the parent DN before submitting the CSV.
     */
    public boolean entryExists(UUID directoryId, AuthPrincipal principal, String dn) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDirectoryAccess(principal, directoryId);
        permissionService.requireBaseDnWithinScope(principal, directoryId, dn);
        return browseService.entryExists(dc, dn);
    }

    /**
     * Creates a single missing parent container at {@code dn} so a bulk
     * import can proceed. The objectClass is inferred from the leftmost RDN
     * (ou/cn → organizationalUnit, o → organization). Audited under
     * {@link AuditAction#USER_CREATE} since this is a directory-write op
     * preceding a user import. The DN must lie within the caller's scope
     * and the immediate parent must already exist (we don't recurse).
     */
    public void createContainer(UUID directoryId, AuthPrincipal principal, String dn) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDnWithinScope(principal, directoryId, dn);
        browseService.createContainer(dc, dn);
        auditService.record(principal, directoryId, AuditAction.USER_CREATE, dn,
                Map.of("kind", "container"));
    }

    // ── Schema ────────────────────────────────────────────────────────────────

    public List<SchemaListItem> getObjectClassNames(UUID directoryId, AuthPrincipal principal) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDirectoryAccess(principal, directoryId);
        return schemaService.getObjectClassNames(dc);
    }

    public List<AttributeTypeInfo> getAttributeTypeNames(UUID directoryId, AuthPrincipal principal) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDirectoryAccess(principal, directoryId);
        return schemaService.getAttributeTypeNames(dc);
    }

    public ObjectClassAttributes getObjectClassAttributes(UUID directoryId, AuthPrincipal principal,
                                                          String objectClass) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDirectoryAccess(principal, directoryId);
        return schemaService.getAttributesForObjectClass(dc, objectClass);
    }

    public ObjectClassAttributes getObjectClassAttributesBulk(UUID directoryId, AuthPrincipal principal,
                                                              List<String> objectClasses) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDirectoryAccess(principal, directoryId);
        return schemaService.getAttributesForObjectClasses(dc, objectClasses);
    }

    public AttributeTypeInfo getAttributeTypeInfo(UUID directoryId, AuthPrincipal principal,
                                                   String attributeName) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDirectoryAccess(principal, directoryId);
        return schemaService.getAttributeTypeInfo(dc, attributeName);
    }

    // ── Users — read ──────────────────────────────────────────────────────────

    public List<LdapEntryResponse> searchUsers(UUID directoryId, AuthPrincipal principal,
                                               String filter, String baseDn,
                                               int limit, String[] attributes) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDirectoryAccess(principal, directoryId);
        // Default to person entries (covers person, organizationalPerson, inetOrgPerson)
        String effectiveFilter = (filter == null || filter.isBlank())
                ? "(objectClass=person)" : filter;
        // Resolve the LDAP search base(s). For admins picking "All"
        // profiles (no explicit baseDn) this fans out across the
        // union of their authorized OUs instead of returning every
        // entry under the directory root.
        List<String> bases = permissionService.resolveSearchBaseDns(principal, directoryId, baseDn);
        if (bases.size() == 1) {
            return userService.searchUsers(dc, effectiveFilter, bases.get(0), limit, attributes)
                    .stream().map(LdapEntryResponse::from).toList();
        }
        List<LdapEntryResponse> merged = new ArrayList<>(limit);
        Set<String> seen = new HashSet<>();
        for (String base : bases) {
            int remaining = limit - merged.size();
            if (remaining <= 0) break;
            for (var u : userService.searchUsers(dc, effectiveFilter, base, remaining, attributes)) {
                if (seen.add(u.getDn().toLowerCase(Locale.ROOT))) {
                    merged.add(LdapEntryResponse.from(u));
                    if (merged.size() >= limit) break;
                }
            }
        }
        return merged;
    }

    public LdapEntryResponse getUser(UUID directoryId, AuthPrincipal principal,
                                     String dn, String[] attributes) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDirectoryAccess(principal, directoryId);
        permissionService.requireDnWithinScope(principal, directoryId, dn);

        return LdapEntryResponse.from(userService.getUser(dc, dn, attributes));
    }

    // ── Users — write ─────────────────────────────────────────────────────────

    public LdapEntryResponse createUser(UUID directoryId, AuthPrincipal principal,
                                        CreateEntryRequest req) {
        return createUser(directoryId, principal, req, null);
    }

    /**
     * Profile-aware overload. {@code profileId} is threaded through to
     * the interceptor chain via {@link UserCreatePayload}. Callers that
     * resolved the profile from the request's DN (UserController.create,
     * ApprovalWorkflowService.executeUserCreate) pass the profile id so
     * downstream interceptors (ISVA, future addons) and the audit
     * detail layer can use it.
     */
    public LdapEntryResponse createUser(UUID directoryId, AuthPrincipal principal,
                                        CreateEntryRequest req, UUID profileId) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDnWithinScope(principal, directoryId, req.dn());

        userService.createUser(dc, req.dn(), req.attributes(), profileId);
        LdapEntryResponse result = LdapEntryResponse.from(userService.getUser(dc, req.dn()));
        Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("attributes", req.attributes().keySet());
        if (profileId != null) detail.put("profileId", profileId.toString());
        auditService.record(principal, directoryId, AuditAction.USER_CREATE, req.dn(), detail);
        return result;
    }

    public LdapEntryResponse updateUser(UUID directoryId, AuthPrincipal principal,
                                        String dn, UpdateEntryRequest req) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDnWithinScope(principal, directoryId, dn);

        List<Modification> mods = toModifications(req);
        userService.updateUser(dc, dn, mods);
        LdapEntryResponse result = LdapEntryResponse.from(userService.getUser(dc, dn));
        auditService.record(principal, directoryId, AuditAction.USER_UPDATE, dn,
                Map.of("modifiedAttributes", req.modifications().stream()
                        .map(AttributeModification::attribute).toList()));
        return result;
    }

    public LdapEntryResponse updateGroup(UUID directoryId, AuthPrincipal principal,
                                         String dn, UpdateEntryRequest req) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDnWithinScope(principal, directoryId, dn);

        List<Modification> mods = toModifications(req);
        groupService.updateGroup(dc, dn, mods);
        LdapEntryResponse result = LdapEntryResponse.from(groupService.getGroup(dc, dn));
        auditService.record(principal, directoryId, AuditAction.GROUP_UPDATE, dn,
                Map.of("modifiedAttributes", req.modifications().stream()
                        .map(AttributeModification::attribute).toList()));
        return result;
    }

    public BulkAttributeUpdateResult bulkUpdateAttributes(UUID directoryId, AuthPrincipal principal,
                                                           BulkAttributeUpdateRequest req) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        req.dns().forEach(dn -> permissionService.requireDnWithinScope(principal, directoryId, dn));

        List<Modification> mods = req.modifications().stream()
                .map(m -> new Modification(
                        toModType(m.operation()),
                        m.attribute(),
                        m.values() == null ? new String[0]
                                : m.values().toArray(new String[0])))
                .toList();

        int updated = 0;
        List<BulkAttributeUpdateResult.BulkUpdateError> failures = new ArrayList<>();

        for (String dn : req.dns()) {
            try {
                userService.updateUser(dc, dn, mods);
                updated++;
            } catch (Exception e) {
                log.warn("Bulk attribute update failed for DN {}: {}", dn, e.getMessage());
                failures.add(new BulkAttributeUpdateResult.BulkUpdateError(dn, e.getMessage()));
            }
        }

        auditService.record(principal, directoryId, AuditAction.BULK_ATTRIBUTE_UPDATE, null,
                Map.of("totalDns", req.dns().size(),
                        "updated", updated,
                        "errors", failures.size(),
                        "modifiedAttributes", req.modifications().stream()
                                .map(AttributeModification::attribute).toList()));

        return new BulkAttributeUpdateResult(updated, failures.size(), failures);
    }

    public void deleteUser(UUID directoryId, AuthPrincipal principal, String dn) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDnWithinScope(principal, directoryId, dn);

        // Resolve the matching profile BEFORE the delete so we can
        // clean up profile-assigned group memberships afterwards.
        // The interceptor chain may turn a delete into a soft-disable
        // (ISVA's default policy), and either way the user's profile
        // group memberships should be removed so reports based on
        // group membership don't see ghost members.
        ProvisioningProfileService ps = profileServiceProvider.getIfAvailable();
        UUID profileId = ps == null ? null
                : ps.resolveProfileForDn(directoryId, dn).map(p -> p.getId()).orElse(null);

        userService.deleteUser(dc, dn, profileId);

        if (ps != null && profileId != null) {
            try {
                ps.removeUserFromProfileGroups(directoryId, profileId, dn, principal);
            } catch (Exception e) {
                // Don't fail the delete for a group-cleanup hiccup —
                // user is gone (or soft-disabled); log + continue.
                log.warn("Failed to clean up profile group memberships for {}: {}",
                        dn, e.getMessage());
            }
        }

        Map<String, Object> detail = new java.util.LinkedHashMap<>();
        if (profileId != null) detail.put("profileId", profileId.toString());
        auditService.record(principal, directoryId, AuditAction.USER_DELETE, dn,
                detail.isEmpty() ? null : detail);
    }

    public void enableUser(UUID directoryId, AuthPrincipal principal, String dn) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDnWithinScope(principal, directoryId, dn);

        userService.enableUser(dc, dn);
        auditService.record(principal, directoryId, AuditAction.USER_ENABLE, dn, null);
    }

    public void disableUser(UUID directoryId, AuthPrincipal principal, String dn) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDnWithinScope(principal, directoryId, dn);

        userService.disableUser(dc, dn);
        auditService.record(principal, directoryId, AuditAction.USER_DISABLE, dn, null);
    }

    public void moveUser(UUID directoryId, AuthPrincipal principal,
                         String dn, MoveUserRequest req) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDnWithinScope(principal, directoryId, dn);
        permissionService.requireDnWithinScope(principal, directoryId, req.newParentDn());

        userService.moveUser(dc, dn, req.newParentDn());
        auditService.record(principal, directoryId, AuditAction.USER_MOVE, dn,
                Map.of("newParentDn", req.newParentDn()));
    }

    public void resetPassword(UUID directoryId, AuthPrincipal principal,
                              String dn, String newPassword) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDnWithinScope(principal, directoryId, dn);

        // Resolve the profile so an interceptor can exempt a FORCE_OFF
        // entry (plain password write, no secUser stamping).
        ProvisioningProfileService ps = profileServiceProvider.getIfAvailable();
        UUID profileId = ps == null ? null
                : ps.resolveProfileForDn(directoryId, dn).map(p -> p.getId()).orElse(null);

        userService.resetPassword(dc, dn, newPassword, profileId);
        auditService.record(principal, directoryId, AuditAction.PASSWORD_RESET, dn, null);
    }

    // ── Groups — read ─────────────────────────────────────────────────────────

    /**
     * LDAP filter that restricts results to well-known group objectClasses.
     */
    private static final String GROUP_OBJECTCLASS_FILTER =
            "(|(objectClass=groupOfNames)(objectClass=groupOfUniqueNames)(objectClass=posixGroup)(objectClass=group)(objectClass=groupOfURLs))";

    public List<LdapEntryResponse> searchGroups(UUID directoryId, AuthPrincipal principal,
                                                String filter, String baseDn,
                                                int limit, String[] attributes) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDirectoryAccess(principal, directoryId);

        // Always intersect with known group objectClasses so non-group entries are excluded.
        String effectiveFilter;
        if (filter == null || filter.isBlank()) {
            effectiveFilter = GROUP_OBJECTCLASS_FILTER;
        } else {
            effectiveFilter = "(&" + filter + GROUP_OBJECTCLASS_FILTER + ")";
        }
        // Same fan-out story as searchUsers — see comment there.
        List<String> bases = permissionService.resolveSearchBaseDns(principal, directoryId, baseDn);
        if (bases.size() == 1) {
            return groupService.searchGroups(dc, effectiveFilter, bases.get(0), limit, attributes)
                    .stream().map(LdapEntryResponse::from).toList();
        }
        List<LdapEntryResponse> merged = new ArrayList<>(limit);
        Set<String> seen = new HashSet<>();
        for (String base : bases) {
            int remaining = limit - merged.size();
            if (remaining <= 0) break;
            for (var g : groupService.searchGroups(dc, effectiveFilter, base, remaining, attributes)) {
                if (seen.add(g.getDn().toLowerCase(Locale.ROOT))) {
                    merged.add(LdapEntryResponse.from(g));
                    if (merged.size() >= limit) break;
                }
            }
        }
        return merged;
    }

    public LdapEntryResponse getGroup(UUID directoryId, AuthPrincipal principal,
                                      String dn, String[] attributes) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDirectoryAccess(principal, directoryId);
        permissionService.requireDnWithinScope(principal, directoryId, dn);

        return LdapEntryResponse.from(groupService.getGroup(dc, dn, attributes));
    }

    public List<String> getGroupMembers(UUID directoryId, AuthPrincipal principal,
                                        String dn, String memberAttribute) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDirectoryAccess(principal, directoryId);
        permissionService.requireDnWithinScope(principal, directoryId, dn);

        return groupService.getMembers(dc, dn, memberAttribute);
    }

    // ── Groups — write ────────────────────────────────────────────────────────

    public LdapEntryResponse createGroup(UUID directoryId, AuthPrincipal principal,
                                         CreateEntryRequest req) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDnWithinScope(principal, directoryId, req.dn());

        groupService.createGroup(dc, req.dn(), req.attributes());
        LdapEntryResponse result = LdapEntryResponse.from(groupService.getGroup(dc, req.dn()));
        auditService.record(principal, directoryId, AuditAction.GROUP_CREATE, req.dn(),
                Map.of("attributes", req.attributes().keySet()));
        return result;
    }

    public void deleteGroup(UUID directoryId, AuthPrincipal principal, String dn) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDnWithinScope(principal, directoryId, dn);

        groupService.deleteGroup(dc, dn);
        auditService.record(principal, directoryId, AuditAction.GROUP_DELETE, dn, null);
    }

    public void addGroupMember(UUID directoryId, AuthPrincipal principal,
                               String groupDn, String memberAttribute, String memberValue) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDnWithinScope(principal, directoryId, groupDn);

        // SoD check — may throw SodViolationException (409) if BLOCK policy is violated
        membershipGate.checkMembership(directoryId, memberValue, groupDn, principal);

        // Resolve the member's profile (memberValue is a DN for
        // member/uniqueMember; a bare uid for memberUid resolves to
        // none → directory behaviour) so an interceptor can exempt a
        // FORCE_OFF member from vendor-specific membership routing.
        ProvisioningProfileService ps = profileServiceProvider.getIfAvailable();
        UUID memberProfileId = ps == null ? null
                : ps.resolveProfileForDn(directoryId, memberValue).map(p -> p.getId()).orElse(null);

        groupService.addMember(dc, groupDn, memberAttribute, memberValue, memberProfileId);
        auditService.record(principal, directoryId, AuditAction.GROUP_MEMBER_ADD, groupDn,
                Map.of("attribute", memberAttribute, "member", memberValue));
    }

    public void removeGroupMember(UUID directoryId, AuthPrincipal principal,
                                  String groupDn, String memberAttribute, String memberValue) {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDnWithinScope(principal, directoryId, groupDn);

        groupService.removeMember(dc, groupDn, memberAttribute, memberValue);
        auditService.record(principal, directoryId, AuditAction.GROUP_MEMBER_REMOVE, groupDn,
                Map.of("attribute", memberAttribute, "member", memberValue));
    }

    // ── Bulk import / export ──────────────────────────────────────────────────

    /**
     * Previews a bulk CSV import without writing to LDAP.
     * Resolves template settings and returns computed DNs for each row.
     */
    public BulkImportPreviewResult previewBulkImport(UUID directoryId, AuthPrincipal principal,
                                                      InputStream csvInput,
                                                      BulkImportRequest req) throws IOException {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDirectoryAccess(principal, directoryId);
        // Scope-check the parent DN the same way the actual import does
        // (bulkImportUsers below). Without this, an admin could preview
        // imports into OUs they don't own — even though the actual
        // import would 403, the preview leaks the OU's existence + the
        // import-validation behaviour for entries the admin can't
        // create.
        permissionService.requireDnWithinScope(principal, directoryId, req.parentDn());

        String targetKeyAttr = "uid";
        List<CsvColumnMappingDto> mappings = req.columnMappings() != null
                ? new ArrayList<>(req.columnMappings()) : new ArrayList<>();
        List<String> objectClasses = List.of();

        if (req.templateId() != null) {
            CsvMappingTemplate template =
                    csvTemplateService.loadTemplate(req.templateId(), directoryId, principal);
            List<CsvMappingTemplateEntry> entries =
                    csvTemplateService.loadEntries(req.templateId());
            targetKeyAttr = template.getTargetKeyAttribute();
            if (mappings.isEmpty()) {
                mappings = entries.stream()
                        .map(e -> new CsvColumnMappingDto(
                                e.getCsvColumnName(), e.getLdapAttribute(), e.isIgnored()))
                        .toList();
            }
            if (template.getObjectClass() != null && !template.getObjectClass().isBlank()) {
                objectClasses = List.of(template.getObjectClass().split("\\s*,\\s*"));
            }
        }

        if (req.targetKeyAttribute() != null) targetKeyAttr = req.targetKeyAttribute();

        boolean skipHeader = resolveSkipHeaderRow(req.skipHeaderRow(), req.templateId(), directoryId, principal);

        // Resolve the object classes' MUST attributes via schema so the preview
        // can flag rows missing values for any of them. Skip attributes that
        // are special-cased (objectClass itself) or unrelated to per-row CSV
        // input (the RDN/key attribute is reported via its own error path
        // when missing). The empty list short-circuits validation when no
        // template is in play.
        List<String> requiredAttrs = List.of();
        if (!objectClasses.isEmpty()) {
            var schema = schemaService.getAttributesForObjectClasses(dc, objectClasses);
            String keyAttr = targetKeyAttr;
            requiredAttrs = schema.required().stream()
                    .filter(a -> !a.equalsIgnoreCase("objectClass"))
                    .filter(a -> !a.equalsIgnoreCase(keyAttr))
                    .toList();
        }

        return bulkUserService.previewImport(
                csvInput, req.parentDn(), targetKeyAttr, mappings, skipHeader, requiredAttrs);
    }

    /**
     * Imports users from a CSV stream into the LDAP directory.
     *
     * <p>Column mappings are resolved from the referenced template (if any),
     * then overridden by any fields set directly on {@code req}.</p>
     */
    public BulkImportResult bulkImportUsers(UUID directoryId, AuthPrincipal principal,
                                            InputStream csvInput,
                                            BulkImportRequest req) throws IOException {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDirectoryAccess(principal, directoryId);
        permissionService.requireDnWithinScope(principal, directoryId, req.parentDn());

        // Defaults — may be overridden by template or request fields
        String            targetKeyAttr    = "uid";
        ConflictHandling  conflictHandling = ConflictHandling.SKIP;
        List<String>      objectClasses    = List.of();
        List<CsvColumnMappingDto> mappings = req.columnMappings() != null
                ? new ArrayList<>(req.columnMappings()) : new ArrayList<>();

        if (req.templateId() != null) {
            CsvMappingTemplate template =
                    csvTemplateService.loadTemplate(req.templateId(), directoryId, principal);
            List<CsvMappingTemplateEntry> entries =
                    csvTemplateService.loadEntries(req.templateId());
            targetKeyAttr    = template.getTargetKeyAttribute();
            conflictHandling = template.getConflictHandling();
            if (template.getObjectClass() != null && !template.getObjectClass().isBlank()) {
                objectClasses = List.of(template.getObjectClass().split(","));
            }
            // Template entries are used only when the request carries no ad-hoc mappings
            if (mappings.isEmpty()) {
                mappings = entries.stream()
                        .map(e -> new CsvColumnMappingDto(
                                e.getCsvColumnName(), e.getLdapAttribute(), e.isIgnored()))
                        .toList();
            }
        }

        // Request-level fields take final precedence
        if (req.targetKeyAttribute() != null)  targetKeyAttr    = req.targetKeyAttribute();
        if (req.conflictHandling()    != null)  conflictHandling = req.conflictHandling();

        boolean skipHeader = resolveSkipHeaderRow(req.skipHeaderRow(), req.templateId(), directoryId, principal);

        // Resolve the matching profile for the import's parent DN. All
        // rows of a bulk import land under the same parent OU, so the
        // profile is the same for every row — no per-row lookup needed.
        // Pass it as a ProfileContext so the bulk service applies the
        // profile's attribute defaults per row and its effective group
        // assignments after each successful create, matching the manual
        // UserController.create + approval-approved create paths.
        ProvisioningProfileService ps = profileServiceProvider.getIfAvailable();
        BulkUserService.ProfileContext profileContext = null;
        if (ps != null) {
            profileContext = ps.resolveProfileForDn(directoryId, req.parentDn())
                    .map(p -> new BulkUserService.ProfileContext(directoryId, p.getId(), principal))
                    .orElse(null);
        }

        BulkImportResult result = bulkUserService.importCsv(
                dc, csvInput, req.parentDn(), targetKeyAttr, conflictHandling, mappings,
                objectClasses, skipHeader, profileContext);

        auditService.record(principal, directoryId, AuditAction.USER_CREATE, req.parentDn(),
                Map.of("operation", "bulkImport",
                       "created",   result.created(),
                       "updated",   result.updated(),
                       "skipped",   result.skipped(),
                       "errors",    result.errors()));
        return result;
    }

    /**
     * Exports directory users to a CSV {@code byte[]} with a header row.
     *
     * <p>When {@code templateId} is supplied and {@code attributes} is empty,
     * the attribute list is derived from the template's non-ignored entries.</p>
     */
    public byte[] bulkExportUsers(UUID directoryId, AuthPrincipal principal,
                                  String filter, String baseDn,
                                  List<String> attributes, UUID templateId) throws IOException {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDirectoryAccess(principal, directoryId);

        // Same fan-out story as searchUsers: admin with no explicit
        // baseDn (e.g. 'All' picker option) gets the union of their
        // authorized OUs, not the directory root. resolveSearchBaseDns
        // also clamps a strict-ancestor baseDn down to the authorized
        // OUs so the CSV doesn't leak entries outside scope.
        List<String> bases = permissionService.resolveSearchBaseDns(principal, directoryId, baseDn);

        List<String> effectiveAttrs = new ArrayList<>(attributes);
        if (effectiveAttrs.isEmpty() && templateId != null) {
            csvTemplateService.loadEntries(templateId).stream()
                    .filter(e -> !e.isIgnored() && e.getLdapAttribute() != null)
                    .map(CsvMappingTemplateEntry::getLdapAttribute)
                    .forEach(effectiveAttrs::add);
        }

        return bulkUserService.exportCsvFromBases(dc, filter, bases, effectiveAttrs);
    }

    // ── Bulk group import / export ───────────────────────────────────────────

    public BulkImportPreviewResult previewBulkGroupImport(UUID directoryId, AuthPrincipal principal,
                                                           InputStream csvInput,
                                                           BulkImportRequest req,
                                                           String objectClass,
                                                           String memberAttribute) throws IOException {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDirectoryAccess(principal, directoryId);
        // Same rationale as previewBulkImport above — keep the preview
        // gated by the same scope check the actual import enforces.
        permissionService.requireDnWithinScope(principal, directoryId, req.parentDn());

        List<CsvColumnMappingDto> mappings = req.columnMappings() != null
                ? new ArrayList<>(req.columnMappings()) : new ArrayList<>();

        if (req.templateId() != null) {
            List<CsvMappingTemplateEntry> entries =
                    csvTemplateService.loadEntries(req.templateId());
            if (mappings.isEmpty()) {
                mappings = entries.stream()
                        .map(e -> new CsvColumnMappingDto(
                                e.getCsvColumnName(), e.getLdapAttribute(), e.isIgnored()))
                        .toList();
            }
        }

        boolean skipHeader = resolveSkipHeaderRow(req.skipHeaderRow(), req.templateId(), directoryId, principal);

        // Resolve the group object class' MUST attributes for per-row preview
        // validation. Same shape as user import: drop objectClass and the cn
        // RDN attribute (cn is special-cased by the import path's
        // missing-key error). 'memberAttribute' tells the preview that a
        // CSV row's 'members' cell satisfies the member/uniqueMember/
        // memberUid requirement, depending on the chosen object class.
        List<String> requiredAttrs = List.of();
        String oc = (objectClass != null && !objectClass.isBlank()) ? objectClass : "groupOfNames";
        var schema = schemaService.getAttributesForObjectClasses(dc, List.of(oc));
        requiredAttrs = schema.required().stream()
                .filter(a -> !a.equalsIgnoreCase("objectClass"))
                .filter(a -> !a.equalsIgnoreCase("cn"))
                .toList();

        return bulkGroupService.previewImport(
                csvInput, req.parentDn(), mappings, skipHeader, requiredAttrs, memberAttribute);
    }

    public BulkImportResult bulkImportGroups(UUID directoryId, AuthPrincipal principal,
                                              InputStream csvInput,
                                              BulkImportRequest req,
                                              String memberAttribute,
                                              String objectClass) throws IOException {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDirectoryAccess(principal, directoryId);
        permissionService.requireDnWithinScope(principal, directoryId, req.parentDn());

        ConflictHandling  conflictHandling = ConflictHandling.SKIP;
        List<String>      objectClasses    = (objectClass != null && !objectClass.isBlank())
                ? List.of(objectClass.split(",")) : List.of("groupOfNames");
        List<CsvColumnMappingDto> mappings = req.columnMappings() != null
                ? new ArrayList<>(req.columnMappings()) : new ArrayList<>();

        if (req.templateId() != null) {
            CsvMappingTemplate template =
                    csvTemplateService.loadTemplate(req.templateId(), directoryId, principal);
            List<CsvMappingTemplateEntry> entries =
                    csvTemplateService.loadEntries(req.templateId());
            conflictHandling = template.getConflictHandling();
            if (template.getObjectClass() != null && !template.getObjectClass().isBlank()) {
                objectClasses = List.of(template.getObjectClass().split(","));
            }
            if (mappings.isEmpty()) {
                mappings = entries.stream()
                        .map(e -> new CsvColumnMappingDto(
                                e.getCsvColumnName(), e.getLdapAttribute(), e.isIgnored()))
                        .toList();
            }
        }

        if (req.conflictHandling() != null) conflictHandling = req.conflictHandling();

        boolean skipHeader = resolveSkipHeaderRow(req.skipHeaderRow(), req.templateId(), directoryId, principal);

        String effectiveMemberAttr = (memberAttribute != null && !memberAttribute.isBlank())
                ? memberAttribute : "member";

        BulkImportResult result = bulkGroupService.importCsv(
                dc, csvInput, req.parentDn(), conflictHandling, mappings,
                objectClasses, effectiveMemberAttr, skipHeader);

        auditService.record(principal, directoryId, AuditAction.GROUP_BULK_IMPORT, req.parentDn(),
                Map.of("operation", "bulkGroupImport",
                       "created",   result.created(),
                       "updated",   result.updated(),
                       "skipped",   result.skipped(),
                       "errors",    result.errors()));
        return result;
    }

    public byte[] bulkExportGroups(UUID directoryId, AuthPrincipal principal,
                                    String filter, String baseDn,
                                    String memberAttribute,
                                    List<String> attributes) throws IOException {
        DirectoryConnection dc = loadDirectory(directoryId, principal);
        permissionService.requireDirectoryAccess(principal, directoryId);

        // See bulkExportUsers for the fan-out rationale.
        List<String> bases = permissionService.resolveSearchBaseDns(principal, directoryId, baseDn);

        return bulkGroupService.exportCsvFromBases(dc, filter, bases, memberAttribute, attributes);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private boolean resolveSkipHeaderRow(Boolean requestValue, UUID templateId,
                                          UUID directoryId, AuthPrincipal principal) {
        if (requestValue != null) return requestValue;
        if (templateId != null) {
            CsvMappingTemplate template =
                    csvTemplateService.loadTemplate(templateId, directoryId, principal);
            return template.isSkipHeaderRow();
        }
        return true; // default: first row is headers
    }

    private DirectoryConnection loadDirectory(UUID directoryId, AuthPrincipal principal) {
        DirectoryConnection dc = dirRepo.findById(directoryId)
                .orElseThrow(() -> new ResourceNotFoundException("DirectoryConnection", directoryId));
        if (!dc.isEnabled()) {
            throw new ResourceNotFoundException("DirectoryConnection", directoryId);
        }
        return dc;
    }

    private List<Modification> toModifications(UpdateEntryRequest req) {
        return req.modifications().stream()
                .map(m -> new Modification(
                        toModType(m.operation()),
                        m.attribute(),
                        m.values() == null ? new String[0]
                                : m.values().toArray(new String[0])))
                .toList();
    }

    private ModificationType toModType(AttributeModification.Operation op) {
        return switch (op) {
            case ADD     -> ModificationType.ADD;
            case REPLACE -> ModificationType.REPLACE;
            case DELETE  -> ModificationType.DELETE;
        };
    }
}
