// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.dto.profile.ApprovalConfigRequest;
import com.ldapportal.dto.profile.CreateProfileRequest;
import com.ldapportal.dto.profile.LifecyclePolicyRequest;
import com.ldapportal.dto.profile.ProfileExportDocument;
import com.ldapportal.dto.profile.ProfileExportDocument.Approval;
import com.ldapportal.dto.profile.ProfileExportDocument.Lifecycle;
import com.ldapportal.dto.profile.ProfileExportDocument.ProfileDefinition;
import com.ldapportal.dto.profile.ProfileImportRequest;
import com.ldapportal.dto.profile.ProfileImportResult;
import com.ldapportal.dto.profile.ProfileImportResult.DnChange;
import com.ldapportal.dto.profile.ProfileResponse;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.ProvisioningProfile;
import com.ldapportal.entity.enums.ApproverMode;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.ProfileApprovalConfigRepository;
import com.ldapportal.repository.ProfileAttributeConfigRepository;
import com.ldapportal.repository.ProfileGroupAssignmentRepository;
import com.ldapportal.repository.ProfileLifecyclePolicyRepository;
import com.ldapportal.repository.ProvisioningProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Provisioning profiles as portable JSON files ({@link ProfileExportDocument}):
 * export one, and import it into a directory of this or another install,
 * either as a new profile or as the user form of an existing one.
 *
 * <p>Import goes through {@link ProvisioningProfileService}'s own create and
 * form-replace paths, so a file gets exactly the checks a manual save does
 * (target OU exists, license limit, RDN field, …). DN-valued settings can be
 * moved from one base DN to another on the way in, since identical
 * directories usually differ only there.
 */
@Service
@RequiredArgsConstructor
public class ProfileExportImportService {

    private final ProvisioningProfileService profileService;
    private final ProvisioningProfileRepository profileRepo;
    private final ProfileAttributeConfigRepository attrConfigRepo;
    private final ProfileGroupAssignmentRepository groupAssignmentRepo;
    private final ProfileLifecyclePolicyRepository lifecycleRepo;
    private final ProfileApprovalConfigRepository approvalConfigRepo;
    private final DirectoryConnectionRepository dirRepo;
    private final AuditService auditService;

    // ── Export ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ProfileExportDocument export(UUID directoryId, UUID profileId) {
        ProvisioningProfile p = profileRepo.findByIdAndDirectoryId(profileId, directoryId)
                .orElseThrow(() -> new ResourceNotFoundException("ProvisioningProfile", profileId));
        DirectoryConnection dir = p.getDirectory();

        Lifecycle lifecycle = lifecycleRepo.findByProfileId(profileId)
                .map(l -> new Lifecycle(l.getExpiresAfterDays(), l.getMaxRenewals(), l.getRenewalDays(),
                        l.getOnExpiryAction(), l.getOnExpiryMoveDn(), l.isOnExpiryRemoveGroups(),
                        l.isOnExpiryNotify(), l.getWarningDaysBefore()))
                .orElse(null);
        Approval approval = approvalConfigRepo.findByProfileId(profileId)
                .map(a -> new Approval(a.isRequireApproval(), a.getApproverMode(), a.getApproverGroupDn(),
                        a.getAutoEscalateDays()))
                .orElse(null);

        ProfileDefinition def = new ProfileDefinition(
                p.getName(), p.getDescription(), p.getThemeColor(),
                p.getTargetUserDn(), p.getTargetGroupDn(), new ArrayList<>(p.getObjectClassNames()),
                p.getRdnAttribute(), p.isShowDnField(), p.getDnTemplate(),
                p.getDnColumnSpan(), p.getDnSectionName(), p.getDnDisplayOrder(),
                p.isSelfRegistrationAllowed(),
                p.getPasswordLength(), p.isPasswordUppercase(), p.isPasswordLowercase(),
                p.isPasswordDigits(), p.isPasswordSpecial(), p.getPasswordSpecialChars(),
                p.isEmailPasswordToUser(),
                p.getPasswordDisposition() != null ? p.getPasswordDisposition().name() : null,
                p.isAutoIncludeGroups(), p.isExcludeAutoIncludes(),
                p.getAdditionalProfiles().stream().map(ProvisioningProfile::getName).sorted().toList(),
                attrConfigRepo.findAllByProfileIdOrderByDisplayOrderAsc(profileId).stream()
                        .map(ProvisioningProfileService::toEntry).toList(),
                groupAssignmentRepo.findAllByProfileIdOrderByDisplayOrderAsc(profileId).stream()
                        .map(g -> new CreateProfileRequest.GroupAssignmentEntry(g.getGroupDn(), g.getMemberAttribute()))
                        .toList(),
                lifecycle, approval);

        return new ProfileExportDocument(ProfileExportDocument.FORMAT_VERSION, ProfileExportDocument.KIND,
                OffsetDateTime.now(),
                new ProfileExportDocument.Source(dir.getDisplayName(), dir.getBaseDn(), p.getName()),
                def);
    }

    // ── Import ────────────────────────────────────────────────────────────────

    /**
     * Import a profile file into {@code directoryId}: as a new disabled profile
     * ({@link ProfileImportRequest.Mode#CREATE}), or as the user form of a
     * profile already there ({@link ProfileImportRequest.Mode#REPLACE_FORM}).
     * All-or-nothing. With {@code dryRun} nothing is saved; the result lists the
     * DN rewrites and warnings the import would produce.
     *
     * @throws IllegalArgumentException for a file this version can't read or a mode missing its inputs
     */
    @Transactional
    public ProfileImportResult importProfile(UUID directoryId, ProfileImportRequest req, AuthPrincipal principal) {
        ProfileExportDocument doc = req.document();
        requireReadable(doc);
        DirectoryConnection dir = dirRepo.findById(directoryId)
                .orElseThrow(() -> new ResourceNotFoundException("DirectoryConnection", directoryId));
        return switch (req.mode()) {
            case CREATE -> importAsNew(dir, doc, req, principal);
            case REPLACE_FORM -> importForm(dir, doc, req, principal);
        };
    }

    private static void requireReadable(ProfileExportDocument doc) {
        if (!ProfileExportDocument.KIND.equals(doc.kind())) {
            throw new IllegalArgumentException("This isn't a provisioning profile export file");
        }
        if (doc.formatVersion() < 1 || doc.formatVersion() > ProfileExportDocument.FORMAT_VERSION) {
            throw new IllegalArgumentException("This file uses profile format version " + doc.formatVersion()
                    + "; this version of the portal reads up to version " + ProfileExportDocument.FORMAT_VERSION
                    + ". Export it again from a matching version, or upgrade this portal.");
        }
    }

    private ProfileImportResult importAsNew(DirectoryConnection dir, ProfileExportDocument doc,
                                            ProfileImportRequest req, AuthPrincipal principal) {
        ProfileDefinition def = doc.profile();
        List<String> warnings = new ArrayList<>();
        List<DnChange> changes = new ArrayList<>();

        // Base-DN rewrite: defaults to "the file's source base DN → this directory's".
        DnRewrite rw = DnRewrite.NONE;
        if (req.rewriteBaseDn()) {
            String from = firstNonBlank(req.baseDnFrom(), doc.source() != null ? doc.source().baseDn() : null);
            String to = firstNonBlank(req.baseDnTo(), dir.getBaseDn());
            if (from == null || to == null) {
                throw new IllegalArgumentException("Base-DN rewrite needs both a base DN to rewrite from and one to rewrite to");
            }
            rw = new DnRewrite(from, to, changes);
        }
        String targetUserDn = rw.apply("Target user OU", def.targetUserDn());
        String targetGroupDn = rw.apply("Target group OU", def.targetGroupDn());
        String dnTemplate = rw.apply("DN template", def.dnTemplate());
        List<CreateProfileRequest.GroupAssignmentEntry> groups = new ArrayList<>();
        for (CreateProfileRequest.GroupAssignmentEntry g : nullToEmpty(def.groupAssignments())) {
            groups.add(new CreateProfileRequest.GroupAssignmentEntry(rw.apply("Group assignment", g.groupDn()),
                    g.memberAttribute()));
        }
        Lifecycle lc = def.lifecycle();
        String moveDn = lc != null ? rw.apply("Lifecycle: move on expiry to", lc.onExpiryMoveDn()) : null;
        Approval ap = def.approval();
        String approverGroupDn = ap != null ? rw.apply("Approver group", ap.approverGroupDn()) : null;

        // Linked "additional profiles" are matched by name in this directory.
        Map<String, UUID> byName = new LinkedHashMap<>();
        profileRepo.findAllByDirectoryIdOrderByNameAsc(dir.getId()).forEach(p -> byName.put(p.getName(), p.getId()));
        List<UUID> additionalIds = new ArrayList<>();
        for (String n : nullToEmpty(def.additionalProfileNames())) {
            UUID id = byName.get(n);
            if (id != null) {
                additionalIds.add(id);
            } else {
                warnings.add("Additional profile \"" + n + "\" doesn't exist in " + dir.getDisplayName()
                        + " and wasn't linked.");
            }
        }
        if (ap != null && ap.requireApproval() && ap.approverMode() != ApproverMode.LDAP_GROUP) {
            warnings.add("The profile requires approval, but approvers aren't part of an export. "
                    + "Add approvers before enabling it.");
        }
        warnings.add("The imported profile starts disabled. Review it, then enable it.");

        String name = firstNonBlank(req.name(), def.name());
        if (req.dryRun()) {
            return new ProfileImportResult(null, changes, warnings);
        }

        CreateProfileRequest create = new CreateProfileRequest(
                name, def.description(), def.themeColor(), targetUserDn, targetGroupDn,
                def.objectClassNames(), def.rdnAttribute(), def.showDnField(), dnTemplate,
                def.dnColumnSpan(), def.dnSectionName(), def.dnDisplayOrder(),
                false, def.selfRegistrationAllowed(),
                def.passwordLength(), def.passwordUppercase(), def.passwordLowercase(),
                def.passwordDigits(), def.passwordSpecial(), def.passwordSpecialChars(),
                def.emailPasswordToUser(), def.passwordDisposition(),
                def.autoIncludeGroups(), def.excludeAutoIncludes(),
                additionalIds, nullToEmpty(def.attributeConfigs()), groups);
        // No principal: create() would audit a plain create; this one is audited below as an import.
        ProfileResponse created = profileService.create(dir.getId(), create, req.force(), null);
        if (lc != null) {
            profileService.setLifecyclePolicy(created.id(), new LifecyclePolicyRequest(
                    lc.expiresAfterDays(), lc.maxRenewals(), lc.renewalDays(), lc.onExpiryAction(), moveDn,
                    lc.onExpiryRemoveGroups(), lc.onExpiryNotify(), lc.warningDaysBefore()));
        }
        if (ap != null) {
            profileService.setApprovalConfig(created.id(), new ApprovalConfigRequest(
                    ap.requireApproval(), ap.approverMode(), approverGroupDn, ap.autoEscalateDays(), null));
        }
        if (principal != null) {
            auditService.record(principal, dir.getId(), AuditAction.PROFILE_CREATE, null,
                    importDetail(created.id(), created.name(), "import", doc));
        }
        return new ProfileImportResult(profileService.get(dir.getId(), created.id()), changes, warnings);
    }

    private ProfileImportResult importForm(DirectoryConnection dir, ProfileExportDocument doc,
                                           ProfileImportRequest req, AuthPrincipal principal) {
        if (req.targetProfileId() == null) {
            throw new IllegalArgumentException("Choose the profile whose form the file replaces");
        }
        ProvisioningProfile target = profileRepo.findByIdAndDirectoryId(req.targetProfileId(), dir.getId())
                .orElseThrow(() -> new ResourceNotFoundException("ProvisioningProfile", req.targetProfileId()));
        if (req.dryRun()) {
            return new ProfileImportResult(null, List.of(), List.of());
        }
        ProfileDefinition def = doc.profile();
        target = profileService.replaceForm(target, def.dnColumnSpan(), def.dnSectionName(), def.dnDisplayOrder(),
                nullToEmpty(def.attributeConfigs()));
        if (principal != null) {
            auditService.record(principal, dir.getId(), AuditAction.PROFILE_UPDATE, null,
                    importDetail(target.getId(), target.getName(), "import-form", doc));
        }
        return new ProfileImportResult(profileService.get(dir.getId(), target.getId()), List.of(), List.of());
    }

    private static Map<String, Object> importDetail(UUID profileId, String name, String operation,
                                                    ProfileExportDocument doc) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("profileId", profileId);
        detail.put("name", name);
        detail.put("operation", operation);
        if (doc.source() != null) {
            if (doc.source().profileName() != null) detail.put("sourceName", doc.source().profileName());
            if (doc.source().directoryName() != null) detail.put("sourceDirectoryName", doc.source().directoryName());
        }
        return detail;
    }

    // ── Base-DN rewrite ───────────────────────────────────────────────────────

    /**
     * Moves DN values from one base DN to another: a value equal to {@code from},
     * or ending in {@code ,from}, gets {@code to} in its place (ignoring case and
     * spaces after commas). Anything else is left alone. Each change is recorded.
     */
    record DnRewrite(String from, String to, List<DnChange> changes) {

        static final DnRewrite NONE = new DnRewrite(null, null, null);

        String apply(String field, String value) {
            if (from == null || value == null || value.isBlank()) return value;
            String rewritten = rewrite(value, from, to);
            if (!rewritten.equals(value)) changes.add(new DnChange(field, value, rewritten));
            return rewritten;
        }

        static String rewrite(String value, String from, String to) {
            String v = norm(value);
            String f = norm(from);
            if (v.equals(f)) return to;
            if (v.endsWith("," + f)) {
                // Cut at the same component boundary in the original, keeping its spelling.
                int components = f.split(",").length;
                int cut = value.length();
                for (int i = 0; i < components; i++) {
                    cut = value.lastIndexOf(',', cut - 1);
                }
                return value.substring(0, cut + 1) + to;
            }
            return value;
        }

        private static String norm(String dn) {
            return dn.trim().replaceAll("\\s*,\\s*", ",").toLowerCase(Locale.ROOT);
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a.trim();
        if (b != null && !b.isBlank()) return b.trim();
        return null;
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list != null ? list : List.of();
    }
}
