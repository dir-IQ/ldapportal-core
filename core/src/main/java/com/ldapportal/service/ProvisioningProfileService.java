// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.dto.profile.*;
import com.ldapportal.dto.profile.CreateProfileRequest.AttributeConfigEntry;
import com.ldapportal.dto.profile.CreateProfileRequest.GroupAssignmentEntry;
import com.ldapportal.entity.*;
import com.ldapportal.entity.enums.ApproverMode;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.entity.enums.ExpiryAction;
import com.ldapportal.entity.enums.InputType;
import com.ldapportal.entity.enums.PasswordDisposition;
import com.ldapportal.exception.ConflictException;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.ldap.LdapGroupService;
import com.ldapportal.ldap.validation.DnValidator;
import com.ldapportal.ldap.LdapUserService;
import com.ldapportal.ldap.model.LdapGroup;
import com.ldapportal.ldap.model.LdapUser;
import com.ldapportal.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Core CRUD and provisioning logic for provisioning profiles.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ProvisioningProfileService {

    /**
     * Hard cap on the length of an attribute value submitted to regex
     * validation. Bounds the worst-case backtracking cost of an admin-
     * supplied {@code validationRegex} — a pathological pattern combined
     * with an unbounded input is the classic ReDoS shape.
     */
    private static final int MAX_REGEX_INPUT_LENGTH = 4096;

    private final ProvisioningProfileRepository      profileRepo;
    private final ProfileAttributeConfigRepository   attrConfigRepo;
    private final ProfileGroupAssignmentRepository   groupAssignmentRepo;
    private final ProfileLifecyclePolicyRepository   lifecycleRepo;
    private final ProfileApprovalConfigRepository    approvalConfigRepo;
    private final ProfileApproverRepository          approverRepo;
    private final DirectoryConnectionRepository      dirRepo;
    private final AccountRepository                  accountRepo;
    private final AdminProfileRoleRepository         adminProfileRoleRepo;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final com.ldapportal.core.entitlement.UsageLimitService usageLimitService;
    private final PasswordGeneratorService passwordGenerator;
    private final LdapUserService ldapUserService;
    private final LdapGroupService ldapGroupService;
    private final com.ldapportal.ldap.LdapBrowseService ldapBrowseService;
    private final com.ldapportal.ldap.LdapSchemaService ldapSchemaService;
    private final AuditService auditService;

    // ── Profile CRUD ──────────────────────────────────────────────────────────

    /**
     * Lists profiles in the given directory, filtered by what {@code principal}
     * is authorized to see. Superadmins get every profile; regular admins get
     * only the ones they have an {@link AdminProfileRole} on. Passing a
     * {@code null} principal bypasses filtering (used by internal callers that
     * have already performed their own authorization check).
     *
     * <p>Filtering here — not just in the UI — is what keeps the profile
     * picker on the Users and Groups pages honest: an admin who crafts the
     * request by hand still can't enumerate profiles they have no role on.</p>
     */
    @Transactional(readOnly = true)
    public List<ProfileResponse> list(UUID directoryId, AuthPrincipal principal) {
        requireDirectory(directoryId);
        List<ProvisioningProfile> all = profileRepo.findAllByDirectoryIdOrderByNameAsc(directoryId);
        if (principal == null || principal.isSuperadmin()) {
            return all.stream().map(this::toResponse).toList();
        }
        Set<UUID> authorizedProfileIds = adminProfileRoleRepo
                .findAllByAdminAccountIdAndProfileDirectoryId(principal.id(), directoryId)
                .stream()
                .map(r -> r.getProfile() != null ? r.getProfile().getId() : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return all.stream()
                .filter(p -> authorizedProfileIds.contains(p.getId()))
                .map(this::toResponse)
                .toList();
    }

    /** Backwards-compatible overload — unfiltered list (superadmin view). */
    @Transactional(readOnly = true)
    public List<ProfileResponse> list(UUID directoryId) {
        return list(directoryId, null);
    }

    @Transactional(readOnly = true)
    public List<ProfileResponse> listAll() {
        return profileRepo.findAllByOrderByDirectoryIdAscNameAsc()
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public ProfileResponse get(UUID profileId) {
        return toResponse(requireProfile(profileId));
    }

    @Transactional(readOnly = true)
    public ProfileResponse get(UUID directoryId, UUID profileId) {
        return toResponse(requireProfileInDirectory(directoryId, profileId));
    }

    @Transactional
    public ProfileResponse create(UUID directoryId, CreateProfileRequest req, AuthPrincipal principal) {
        return create(directoryId, req, false, principal);
    }

    /**
     * Same as {@link #create(UUID, CreateProfileRequest, AuthPrincipal)} but
     * with a {@code force} flag that skips the
     * {@link #requireTargetOuExists target-OU existence check}. The
     * default-false call sites are protected against the typo case;
     * the {@code force=true} controller path is for the legitimate
     * pre-stage workflow (admin creates a profile before the OU is
     * provisioned in LDAP).
     */
    @Transactional
    public ProfileResponse create(UUID directoryId, CreateProfileRequest req,
                                   boolean force, AuthPrincipal principal) {
        DirectoryConnection dir = requireDirectory(directoryId);

        // License cap is per-directory — customers on tighter tiers get a
        // small number of profiles per directory rather than a global
        // total. PROFILES_PER_DIRECTORY maps directly to that.
        usageLimitService.requireWithinLimit(
                com.ldapportal.core.entitlement.LimitType.PROFILES_PER_DIRECTORY,
                profileRepo.countByDirectoryId(directoryId));

        if (!force) {
            requireTargetOuExists(dir, req.targetUserDn());
        }

        if (profileRepo.existsByDirectoryIdAndName(directoryId, req.name())) {
            throw new ConflictException(
                    "Profile [" + req.name() + "] already exists in this directory");
        }

        ProvisioningProfile profile = new ProvisioningProfile();
        profile.setDirectory(dir);
        profile.setSlug(resolveSlug(req.name()));
        applyCommonFields(profile, req.name(), req.description(), req.targetUserDn(),
                req.targetGroupDn(), req.objectClassNames(), req.rdnAttribute(), req.showDnField(),
                req.dnTemplate(), req.dnColumnSpan(), req.dnSectionName(), req.dnDisplayOrder(),
                req.enabled(), req.selfRegistrationAllowed(),
                req.passwordLength(), req.passwordUppercase(), req.passwordLowercase(),
                req.passwordDigits(), req.passwordSpecial(), req.passwordSpecialChars(),
                req.emailPasswordToUser(), req.passwordDisposition());
        profile.setThemeColor(normalizeThemeColor(req.themeColor()));
        profile.setAutoIncludeGroups(req.autoIncludeGroups());
        // Auto-include profiles should not also exclude auto-includes (nonsensical)
        profile.setExcludeAutoIncludes(req.autoIncludeGroups() ? false : req.excludeAutoIncludes());
        profile = profileRepo.save(profile);

        saveAdditionalProfiles(profile, req.additionalProfileIds());
        saveAttributeConfigs(profile, req.attributeConfigs());
        saveGroupAssignments(profile, req.groupAssignments(), !force);

        if (principal != null) {
            auditService.record(principal, directoryId, AuditAction.PROFILE_CREATE, null,
                    Map.of("profileId", profile.getId(), "name", profile.getName(),
                            "targetUserDn", profile.getTargetUserDn(),
                            "targetGroupDn", profile.getTargetGroupDn()));
        }

        return toResponse(profile);
    }

    /** Backwards-compatible overload — preserves the no-principal call sites. */
    @Transactional
    public ProfileResponse create(UUID directoryId, CreateProfileRequest req) {
        return create(directoryId, req, null);
    }

    // ── Slug (IaC external key) helpers ────────────────────────────────────────

    /**
     * Derive a clean, globally-unique slug from the profile name, appending a
     * numeric suffix on collision ({@code name}, {@code name-2}, …). The slug is
     * global (not per-directory) so it's a single stable key that admin
     * profile-role / ISVA-override references can address.
     */
    private String resolveSlug(String name) {
        String base = slugify(name);
        String candidate = base;
        int n = 2;
        while (profileRepo.existsBySlug(candidate)) {
            candidate = base + "-" + n++;
        }
        return candidate;
    }

    private String slugify(String name) {
        String base = name == null ? "" : name
                .trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        if (base.length() > 100) {
            base = base.substring(0, 100).replaceAll("-+$", "");
        }
        return base.isEmpty() ? "profile" : base;
    }

    @Transactional
    public ProfileResponse update(UUID directoryId, UUID profileId, UpdateProfileRequest req) {
        return update(directoryId, profileId, req, false, null);
    }

    @Transactional
    public ProfileResponse update(UUID directoryId, UUID profileId, UpdateProfileRequest req,
                                   AuthPrincipal principal) {
        return update(directoryId, profileId, req, false, principal);
    }

    /**
     * Update with the same {@code force} flag semantics as
     * {@link #create(UUID, CreateProfileRequest, boolean, AuthPrincipal)}.
     *
     * <p>Update only re-validates the target OU when it actually
     * changed between the stored value and the request. An admin
     * editing other fields (display name, description, attribute
     * configs) shouldn't get blocked because the OU disappeared
     * out-of-band — the original deliberate save committed to that
     * OU, and changing unrelated fields shouldn't re-litigate it.</p>
     */
    @Transactional
    public ProfileResponse update(UUID directoryId, UUID profileId, UpdateProfileRequest req,
                                   boolean force, AuthPrincipal principal) {
        ProvisioningProfile profile = requireProfileInDirectory(directoryId, profileId);

        // Check name uniqueness if changed
        if (!profile.getName().equals(req.name()) &&
                profileRepo.existsByDirectoryIdAndName(profile.getDirectory().getId(), req.name())) {
            throw new ConflictException(
                    "Profile [" + req.name() + "] already exists in this directory");
        }

        boolean targetUserDnChanged = !profile.getTargetUserDn().equalsIgnoreCase(req.targetUserDn());
        if (!force && targetUserDnChanged) {
            requireTargetOuExists(profile.getDirectory(), req.targetUserDn());
        }

        applyCommonFields(profile, req.name(), req.description(), req.targetUserDn(),
                req.targetGroupDn(), req.objectClassNames(), req.rdnAttribute(), req.showDnField(),
                req.dnTemplate(), req.dnColumnSpan(), req.dnSectionName(), req.dnDisplayOrder(),
                req.enabled(), req.selfRegistrationAllowed(),
                req.passwordLength(), req.passwordUppercase(), req.passwordLowercase(),
                req.passwordDigits(), req.passwordSpecial(), req.passwordSpecialChars(),
                req.emailPasswordToUser(), req.passwordDisposition());
        profile.setThemeColor(normalizeThemeColor(req.themeColor()));
        profile.setAutoIncludeGroups(req.autoIncludeGroups());
        // Auto-include profiles should not also exclude auto-includes (nonsensical)
        profile.setExcludeAutoIncludes(req.autoIncludeGroups() ? false : req.excludeAutoIncludes());
        profile = profileRepo.save(profile);

        // Replace additional profiles
        saveAdditionalProfiles(profile, req.additionalProfileIds());

        // Replace attribute configs
        attrConfigRepo.deleteAllByProfileId(profileId);
        attrConfigRepo.flush();
        saveAttributeConfigs(profile, req.attributeConfigs());

        // Replace group assignments
        groupAssignmentRepo.deleteAllByProfileId(profileId);
        groupAssignmentRepo.flush();
        saveGroupAssignments(profile, req.groupAssignments(), !force);

        if (principal != null) {
            auditService.record(principal, directoryId, AuditAction.PROFILE_UPDATE, null,
                    Map.of("profileId", profile.getId(), "name", profile.getName()));
        }

        return toResponse(profile);
    }

    @Transactional
    public void delete(UUID directoryId, UUID profileId) {
        delete(directoryId, profileId, null);
    }

    @Transactional
    public void delete(UUID directoryId, UUID profileId, AuthPrincipal principal) {
        ProvisioningProfile profile = requireProfileInDirectory(directoryId, profileId);
        String name = profile.getName();
        profileRepo.delete(profile);
        if (principal != null) {
            auditService.record(principal, directoryId, AuditAction.PROFILE_DELETE, null,
                    Map.of("profileId", profileId, "name", name));
        }
    }

    @Transactional
    public ProfileResponse clone(UUID directoryId, UUID profileId, String newName) {
        return clone(directoryId, profileId, newName, null);
    }

    @Transactional
    public ProfileResponse clone(UUID directoryId, UUID profileId, String newName,
                                  AuthPrincipal principal) {
        ProvisioningProfile source = requireProfileInDirectory(directoryId, profileId);

        if (profileRepo.existsByDirectoryIdAndName(source.getDirectory().getId(), newName)) {
            throw new ConflictException(
                    "Profile [" + newName + "] already exists in this directory");
        }

        ProvisioningProfile copy = new ProvisioningProfile();
        copy.setDirectory(source.getDirectory());
        copy.setName(newName);
        copy.setDescription(source.getDescription());
        copy.setThemeColor(source.getThemeColor());
        copy.setTargetUserDn(source.getTargetUserDn());
        copy.setTargetGroupDn(source.getTargetGroupDn());
        copy.setObjectClassNames(new ArrayList<>(source.getObjectClassNames()));
        copy.setRdnAttribute(source.getRdnAttribute());
        copy.setShowDnField(source.isShowDnField());
        copy.setDnTemplate(source.getDnTemplate());
        copy.setDnColumnSpan(source.getDnColumnSpan());
        copy.setDnSectionName(source.getDnSectionName());
        copy.setDnDisplayOrder(source.getDnDisplayOrder());
        copy.setEnabled(false); // clones start disabled
        copy.setSelfRegistrationAllowed(false);
        copy.setPasswordLength(source.getPasswordLength());
        copy.setPasswordUppercase(source.isPasswordUppercase());
        copy.setPasswordLowercase(source.isPasswordLowercase());
        copy.setPasswordDigits(source.isPasswordDigits());
        copy.setPasswordSpecial(source.isPasswordSpecial());
        copy.setPasswordSpecialChars(source.getPasswordSpecialChars());
        copy.setEmailPasswordToUser(source.isEmailPasswordToUser());
        copy.setAutoIncludeGroups(false); // clones don't auto-include
        copy.setExcludeAutoIncludes(source.isExcludeAutoIncludes());
        copy.setAdditionalProfiles(new HashSet<>(source.getAdditionalProfiles()));
        copy = profileRepo.save(copy);

        // Clone attribute configs — every persistable column must be copied
        // or the clone silently loses self-registration / self-service form
        // layout (the missing setSelfRegistrationEdit + registration*/
        // selfService* fields were the original bug here).
        List<ProfileAttributeConfig> sourceConfigs =
                attrConfigRepo.findAllByProfileIdOrderByDisplayOrderAsc(profileId);
        for (ProfileAttributeConfig sc : sourceConfigs) {
            ProfileAttributeConfig cc = new ProfileAttributeConfig();
            cc.setProfile(copy);
            cc.setAttributeName(sc.getAttributeName());
            cc.setCustomLabel(sc.getCustomLabel());
            cc.setInputType(sc.getInputType());
            cc.setRequiredOnCreate(sc.isRequiredOnCreate());
            cc.setEditableOnCreate(sc.isEditableOnCreate());
            cc.setEditableOnUpdate(sc.isEditableOnUpdate());
            cc.setSelfServiceEdit(sc.isSelfServiceEdit());
            cc.setSelfRegistrationEdit(sc.isSelfRegistrationEdit());
            cc.setDefaultValue(sc.getDefaultValue());
            cc.setComputedExpression(sc.getComputedExpression());
            cc.setValidationRegex(sc.getValidationRegex());
            cc.setValidationMessage(sc.getValidationMessage());
            cc.setAllowedValues(sc.getAllowedValues());
            cc.setMinLength(sc.getMinLength());
            cc.setMaxLength(sc.getMaxLength());
            cc.setSectionName(sc.getSectionName());
            cc.setColumnSpan(sc.getColumnSpan());
            cc.setDisplayOrder(sc.getDisplayOrder());
            cc.setHidden(sc.isHidden());
            cc.setRegistrationSectionName(sc.getRegistrationSectionName());
            cc.setRegistrationColumnSpan(sc.getRegistrationColumnSpan());
            cc.setRegistrationDisplayOrder(sc.getRegistrationDisplayOrder());
            cc.setSelfServiceSectionName(sc.getSelfServiceSectionName());
            cc.setSelfServiceColumnSpan(sc.getSelfServiceColumnSpan());
            cc.setSelfServiceDisplayOrder(sc.getSelfServiceDisplayOrder());
            attrConfigRepo.save(cc);
        }

        // Clone group assignments
        List<ProfileGroupAssignment> sourceGroups =
                groupAssignmentRepo.findAllByProfileIdOrderByDisplayOrderAsc(profileId);
        for (ProfileGroupAssignment sg : sourceGroups) {
            ProfileGroupAssignment cg = new ProfileGroupAssignment();
            cg.setProfile(copy);
            cg.setGroupDn(sg.getGroupDn());
            cg.setMemberAttribute(sg.getMemberAttribute());
            cg.setDisplayOrder(sg.getDisplayOrder());
            groupAssignmentRepo.save(cg);
        }

        if (principal != null) {
            auditService.record(principal, directoryId, AuditAction.PROFILE_CLONE, null,
                    Map.of("profileId", copy.getId(), "name", copy.getName(),
                            "sourceProfileId", source.getId(), "sourceName", source.getName()));
        }
        // Clone lifecycle policy (structural config — copying is the
        // "least surprising" default).
        final ProvisioningProfile cloned = copy;
        lifecycleRepo.findByProfileId(profileId).ifPresent(srcPolicy -> {
            ProfileLifecyclePolicy cp = new ProfileLifecyclePolicy();
            cp.setProfile(cloned);
            cp.setExpiresAfterDays(srcPolicy.getExpiresAfterDays());
            cp.setMaxRenewals(srcPolicy.getMaxRenewals());
            cp.setRenewalDays(srcPolicy.getRenewalDays());
            cp.setOnExpiryAction(srcPolicy.getOnExpiryAction());
            cp.setOnExpiryMoveDn(srcPolicy.getOnExpiryMoveDn());
            cp.setOnExpiryRemoveGroups(srcPolicy.isOnExpiryRemoveGroups());
            cp.setOnExpiryNotify(srcPolicy.isOnExpiryNotify());
            cp.setWarningDaysBefore(srcPolicy.getWarningDaysBefore());
            lifecycleRepo.save(cp);
        });

        // Clone approval config (structural) but NOT approvers (people).
        // Copying approvers would silently grant a list of admins approve
        // power on a profile they may not be intended to govern; the clone
        // is disabled by default so leaving approvers empty is benign.
        approvalConfigRepo.findByProfileId(profileId).ifPresent(srcCfg -> {
            ProfileApprovalConfig cc = new ProfileApprovalConfig();
            cc.setProfile(cloned);
            cc.setRequireApproval(srcCfg.isRequireApproval());
            cc.setApproverMode(srcCfg.getApproverMode());
            cc.setApproverGroupDn(srcCfg.getApproverGroupDn());
            cc.setAutoEscalateDays(srcCfg.getAutoEscalateDays());
            cc.setEscalationAccount(srcCfg.getEscalationAccount());
            approvalConfigRepo.save(cc);
        });

        return toResponse(copy);
    }

    // ── Lifecycle Policy ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public LifecyclePolicyResponse getLifecyclePolicy(UUID profileId) {
        requireProfile(profileId);
        ProfileLifecyclePolicy policy = lifecycleRepo.findByProfileId(profileId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No lifecycle policy for profile " + profileId));
        return LifecyclePolicyResponse.from(policy);
    }

    @Transactional
    public LifecyclePolicyResponse setLifecyclePolicy(UUID profileId, LifecyclePolicyRequest req) {
        ProvisioningProfile profile = requireProfile(profileId);
        ProfileLifecyclePolicy policy = lifecycleRepo.findByProfileId(profileId)
                .orElseGet(() -> {
                    ProfileLifecyclePolicy p = new ProfileLifecyclePolicy();
                    p.setProfile(profile);
                    return p;
                });

        policy.setExpiresAfterDays(req.expiresAfterDays());
        policy.setMaxRenewals(req.maxRenewals());
        policy.setRenewalDays(req.renewalDays());
        policy.setOnExpiryAction(req.onExpiryAction() != null ? req.onExpiryAction() : ExpiryAction.DISABLE);
        policy.setOnExpiryMoveDn(req.onExpiryMoveDn());
        policy.setOnExpiryRemoveGroups(req.onExpiryRemoveGroups());
        policy.setOnExpiryNotify(req.onExpiryNotify());
        policy.setWarningDaysBefore(req.warningDaysBefore());

        return LifecyclePolicyResponse.from(lifecycleRepo.save(policy));
    }

    @Transactional
    public void deleteLifecyclePolicy(UUID profileId) {
        requireProfile(profileId);
        lifecycleRepo.deleteByProfileId(profileId);
    }

    // ── Approval Config ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ApprovalConfigResponse getApprovalConfig(UUID profileId) {
        requireProfile(profileId);
        ProfileApprovalConfig config = approvalConfigRepo.findByProfileId(profileId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No approval config for profile " + profileId));
        return ApprovalConfigResponse.from(config);
    }

    @Transactional
    public ApprovalConfigResponse setApprovalConfig(UUID profileId, ApprovalConfigRequest req) {
        ProvisioningProfile profile = requireProfile(profileId);
        ProfileApprovalConfig config = approvalConfigRepo.findByProfileId(profileId)
                .orElseGet(() -> {
                    ProfileApprovalConfig c = new ProfileApprovalConfig();
                    c.setProfile(profile);
                    return c;
                });

        ApproverMode mode = req.approverMode() != null ? req.approverMode() : ApproverMode.DATABASE;

        // Coherence checks — a profile with requireApproval=true must have a
        // working approver source, otherwise every user-create lands in a
        // pending-approval queue nobody can clear.
        if (req.requireApproval() && mode == ApproverMode.LDAP_GROUP
                && (req.approverGroupDn() == null || req.approverGroupDn().isBlank())) {
            throw new IllegalArgumentException(
                    "LDAP_GROUP approver mode requires a non-empty approver group DN");
        }

        config.setRequireApproval(req.requireApproval());
        config.setApproverMode(mode);
        // DATABASE mode doesn't use a group DN; clear any stale value so the
        // stored config can't disagree with itself.
        config.setApproverGroupDn(mode == ApproverMode.LDAP_GROUP ? req.approverGroupDn() : null);
        config.setAutoEscalateDays(req.autoEscalateDays());
        if (req.escalationAccountId() != null) {
            config.setEscalationAccount(accountRepo.findById(req.escalationAccountId())
                    .orElseThrow(() -> new ResourceNotFoundException("Account", req.escalationAccountId())));
        } else {
            config.setEscalationAccount(null);
        }

        return ApprovalConfigResponse.from(approvalConfigRepo.save(config));
    }

    // ── Approvers ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ProfileApproverResponse> getApprovers(UUID profileId) {
        requireProfile(profileId);
        return approverRepo.findAllByProfileIdWithAccount(profileId).stream()
                .map(pa -> new ProfileApproverResponse(
                        pa.getAdminAccount().getId(),
                        pa.getAdminAccount().getUsername(),
                        pa.getAdminAccount().getEmail()))
                .toList();
    }

    @Transactional
    public List<ProfileApproverResponse> setApprovers(UUID profileId, List<UUID> accountIds) {
        ProvisioningProfile profile = requireProfile(profileId);
        approverRepo.deleteAllByProfileId(profileId);
        approverRepo.flush();

        for (UUID accountId : accountIds) {
            Account account = accountRepo.findById(accountId)
                    .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
            // The UI already filters by admin role; the backend enforces it
            // so a hand-crafted request can't silently wedge approvals on
            // accounts that lack approve permission.
            if (account.getRole() != com.ldapportal.entity.enums.AccountRole.ADMIN
                    && account.getRole() != com.ldapportal.entity.enums.AccountRole.SUPERADMIN) {
                throw new IllegalArgumentException(
                        "Account [" + account.getUsername()
                                + "] is not an admin and cannot be a profile approver");
            }
            ProfileApprover pa = new ProfileApprover();
            pa.setProfile(profile);
            pa.setAdminAccount(account);
            approverRepo.save(pa);
        }

        return approverRepo.findAllByProfileIdWithAccount(profileId).stream()
                .map(pa -> new ProfileApproverResponse(
                        pa.getAdminAccount().getId(),
                        pa.getAdminAccount().getUsername(),
                        pa.getAdminAccount().getEmail()))
                .toList();
    }

    // ── Profile resolution ────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Optional<ProvisioningProfile> resolveProfileForDn(UUID directoryId, String dn) {
        List<ProvisioningProfile> profiles =
                profileRepo.findAllByDirectoryIdAndEnabledTrue(directoryId);
        String dnLower = dn.toLowerCase();

        // Find profiles whose target OU is a suffix of the DN; most specific match wins
        return profiles.stream()
                .filter(p -> dnLower.endsWith(p.getTargetUserDn().toLowerCase()))
                .max(Comparator.comparingInt(p -> p.getTargetUserDn().length()));
    }

    /**
     * Enforces that {@code dn} — typically an admin-overridden new-user DN —
     * stays within the profile's target-OU subtree. The admin create form lets
     * the (otherwise computed) DN be edited, so this is the server-side
     * guardrail that a hand-edited DN can't drop a user outside the profile's
     * DIT. No-op for Entra ID, whose objects are addressed by id/UPN, not DN.
     *
     * @throws IllegalArgumentException (mapped to 400) when the DN lies outside
     *                                  the profile's {@code targetUserDn} subtree
     */
    @Transactional(readOnly = true)
    public void requireDnWithinProfileDit(UUID profileId, String dn) {
        ProvisioningProfile profile = requireProfile(profileId);
        if (profile.getDirectory().getDirectoryType() == DirectoryType.ENTRA_ID) {
            return;
        }
        // Distinguish "not a valid DN" from "valid but out of scope": an
        // unescaped reserved char (e.g. a bare '+', the multi-valued-RDN
        // separator) makes the DN unparseable, and a bare containment check
        // would otherwise mis-report it as "outside the target OU".
        if (!DnValidator.isValidDn(dn)) {
            throw new IllegalArgumentException(
                    "User DN [" + dn + "] is not a valid distinguished name "
                            + "(reserved characters such as '+' or ',' in a value must be \\-escaped)");
        }
        if (!DnValidator.isWithinSubtree(dn, profile.getTargetUserDn())) {
            throw new IllegalArgumentException(
                    "User DN [" + dn + "] is outside the profile's target OU ["
                            + profile.getTargetUserDn() + "]");
        }
    }

    @Transactional(readOnly = true)
    public boolean isApprovalRequired(UUID profileId) {
        return approvalConfigRepo.findByProfileId(profileId)
                .map(ProfileApprovalConfig::isRequireApproval)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean isApprover(UUID profileId, UUID accountId) {
        return approverRepo.existsByProfileIdAndAdminAccountId(profileId, accountId);
    }

    // ── Provisioning helpers ──────────────────────────────────────────────────

    /**
     * Evaluates computed expressions by tokenizing into variable references
     * ({@code ${attr}}), quoted string literals, concatenation operators (+),
     * and literal text.  No regex used for the concatenation handling.
     */
    public String evaluateExpression(String expression, Map<String, List<String>> attributes) {
        if (expression == null || expression.isBlank()) return null;

        StringBuilder result = new StringBuilder();
        int i = 0;
        int len = expression.length();
        while (i < len) {
            char c = expression.charAt(i);
            if (c == '$' && i + 1 < len && expression.charAt(i + 1) == '{') {
                // Variable reference: ${attrName}
                int end = expression.indexOf('}', i + 2);
                if (end == -1) break;
                String attrName = expression.substring(i + 2, end);
                List<String> values = attributes.get(attrName);
                result.append((values != null && !values.isEmpty()) ? values.get(0) : "");
                i = end + 1;
            } else if (c == '+') {
                // Concatenation operator — skip it
                i++;
            } else if (c == '"' || c == '\'') {
                // Quoted string literal
                int end = expression.indexOf(c, i + 1);
                if (end == -1) break;
                result.append(expression, i + 1, end);
                i = end + 1;
            } else {
                // Literal text (dots, @domain, or a lone '$'/operator char that
                // didn't start a ${...} reference). Scan from i+1 so the current
                // char is always consumed — otherwise a '$' not followed by '{'
                // would never advance i and the loop would spin forever.
                int j = i + 1;
                while (j < len) {
                    char ch = expression.charAt(j);
                    if (ch == '$' || ch == '+' || ch == '"' || ch == '\'') break;
                    j++;
                }
                result.append(expression, i, j);
                i = j;
            }
        }
        return result.toString();
    }

    /**
     * Validates attribute values against profile attribute configs.
     */
    @Transactional(readOnly = true)
    public void validateAttributes(UUID profileId, Map<String, List<String>> attributes) {
        List<ProfileAttributeConfig> configs =
                attrConfigRepo.findAllByProfileIdOrderByDisplayOrderAsc(profileId);

        for (ProfileAttributeConfig config : configs) {
            List<String> values = attributes.get(config.getAttributeName());
            String value = (values != null && !values.isEmpty()) ? values.get(0) : null;

            // Required check
            if (config.isRequiredOnCreate() && (value == null || value.isBlank())) {
                throw new IllegalArgumentException(
                        "Attribute [" + config.getAttributeName() + "] is required");
            }

            if (value == null || value.isBlank()) continue;

            validateValueConstraints(config, value);
        }
    }

    /**
     * Configured input types for a profile, keyed by <strong>lower-case</strong>
     * attribute name. Feeds the syntax layer
     * ({@code LdapAttributeValidator.validateSyntax}) so a {@code DN_LOOKUP} /
     * {@code BOOLEAN} field is shape-checked authoritatively on write. Returns an
     * empty map for a {@code null} profile (an unprofiled OU still runs
     * well-known-attribute syntax checks).
     */
    @Transactional(readOnly = true)
    public Map<String, InputType> inputTypesForProfile(UUID profileId) {
        if (profileId == null) {
            return Map.of();
        }
        Map<String, InputType> types = new HashMap<>();
        for (ProfileAttributeConfig config : attrConfigRepo.findAllByProfileIdOrderByDisplayOrderAsc(profileId)) {
            if (config.getInputType() != null) {
                types.put(config.getAttributeName().toLowerCase(Locale.ROOT), config.getInputType());
            }
        }
        return types;
    }

    /**
     * Validates a modification (update) against the profile's configs in a
     * <strong>single</strong> config fetch: rejects edits to non-editable or
     * hidden attributes, and checks value constraints (length / regex /
     * allowed-values) on the attributes being set. {@code requiredOnCreate} is
     * intentionally not enforced — an attribute absent from this update is not
     * a missing-required error.
     *
     * @param modifiedNames  attribute names targeted by the update, any
     *                       operation (including DELETE)
     * @param modifiedValues attribute name → values being set (ADD/REPLACE only)
     */
    @Transactional(readOnly = true)
    public void validateModification(UUID profileId,
                                     Collection<String> modifiedNames,
                                     Map<String, List<String>> modifiedValues) {
        List<ProfileAttributeConfig> configs =
                attrConfigRepo.findAllByProfileIdOrderByDisplayOrderAsc(profileId);
        assertEditable(configs, modifiedNames);
        validateModifiedValues(configs, modifiedValues);
    }

    /**
     * Rejects an attempt to modify attributes the profile marks non-editable on
     * update or hidden, mirroring the edit-form field gating so an API caller
     * cannot bypass it. System-computed attributes (carrying a
     * {@code computedExpression}) are exempt — they are set by the server, not
     * the user. Attributes without a profile config are unrestricted.
     */
    private void assertEditable(List<ProfileAttributeConfig> configs, Collection<String> attributeNames) {
        if (attributeNames == null || attributeNames.isEmpty()) return;
        Set<String> targeted = attributeNames.stream()
                .filter(Objects::nonNull)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        for (ProfileAttributeConfig config : configs) {
            if (!targeted.contains(config.getAttributeName().toLowerCase(Locale.ROOT))) continue;
            boolean computed = config.getComputedExpression() != null
                    && !config.getComputedExpression().isBlank();
            if (computed) continue;
            if (!config.isEditableOnUpdate() || config.isHidden()) {
                throw new IllegalArgumentException(
                        "Attribute [" + config.getAttributeName() + "] is not editable on update");
            }
        }
    }

    /**
     * Value-constraint checks (length / regex / allowed-values) for the
     * attributes present in {@code attributes}. Does not enforce
     * {@code requiredOnCreate} (update path).
     */
    private void validateModifiedValues(List<ProfileAttributeConfig> configs, Map<String, List<String>> attributes) {
        for (ProfileAttributeConfig config : configs) {
            List<String> values = attributes.get(config.getAttributeName());
            String value = (values != null && !values.isEmpty()) ? values.get(0) : null;
            if (value == null || value.isBlank()) continue; // not part of this modification
            validateValueConstraints(config, value);
        }
    }

    /**
     * Length / regex / allowed-values checks for a single attribute value.
     * Shared by {@link #validateAttributes} (create) and
     * {@link #validateModification} (update).
     */
    private void validateValueConstraints(ProfileAttributeConfig config, String value) {
        // Length checks
        if (config.getMinLength() != null && value.length() < config.getMinLength()) {
            throw new IllegalArgumentException(
                    "Attribute [" + config.getAttributeName() + "] must be at least "
                            + config.getMinLength() + " characters");
        }
        if (config.getMaxLength() != null && value.length() > config.getMaxLength()) {
            throw new IllegalArgumentException(
                    "Attribute [" + config.getAttributeName() + "] must be at most "
                            + config.getMaxLength() + " characters");
        }

        // Regex check — guard against ReDoS by capping input length and
        // catching PatternSyntaxException defensively. The pattern is
        // also validated at config save time in saveAttributeConfigs.
        if (config.getValidationRegex() != null && !config.getValidationRegex().isBlank()) {
            if (value.length() > MAX_REGEX_INPUT_LENGTH) {
                throw new IllegalArgumentException(
                        "Attribute [" + config.getAttributeName() + "] exceeds the "
                                + MAX_REGEX_INPUT_LENGTH + "-character limit for regex-validated fields");
            }
            boolean matches;
            try {
                matches = Pattern.matches(config.getValidationRegex(), value);
            } catch (PatternSyntaxException pse) {
                log.warn("Invalid validation regex for attribute [{}]; rejecting value: {}",
                        config.getAttributeName(), pse.getDescription());
                throw new IllegalArgumentException(
                        "Attribute [" + config.getAttributeName()
                                + "] cannot be validated: validation pattern is invalid");
            }
            if (!matches) {
                String msg = config.getValidationMessage() != null
                        ? config.getValidationMessage()
                        : "Attribute [" + config.getAttributeName() + "] does not match the required format";
                throw new IllegalArgumentException(msg);
            }
        }

        // Allowed values check
        if (config.getAllowedValues() != null && !config.getAllowedValues().isBlank()) {
            try {
                List<String> allowed = objectMapper.readValue(config.getAllowedValues(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                if (!allowed.contains(value)) {
                    throw new IllegalArgumentException(
                            "Attribute [" + config.getAttributeName()
                                    + "] value is not in the allowed values list");
                }
            } catch (IllegalArgumentException iae) {
                throw iae;
            } catch (Exception e) {
                log.warn("Failed to parse allowed values JSON for attribute [{}]: {}",
                        config.getAttributeName(), e.getMessage());
            }
        }
    }

    /**
     * Applies defaults, computed expressions, and fixed values to the attribute map.
     */
    @Transactional(readOnly = true)
    public void applyDefaults(UUID profileId, Map<String, List<String>> attributes) {
        List<ProfileAttributeConfig> configs =
                attrConfigRepo.findAllByProfileIdOrderByDisplayOrderAsc(profileId);

        for (ProfileAttributeConfig config : configs) {
            List<String> values = attributes.get(config.getAttributeName());
            boolean hasValue = values != null && !values.isEmpty()
                    && values.get(0) != null && !values.get(0).isBlank();

            // Apply HIDDEN_FIXED values always
            if (config.getInputType() == InputType.HIDDEN_FIXED && config.getDefaultValue() != null) {
                attributes.put(config.getAttributeName(), List.of(config.getDefaultValue()));
                continue;
            }

            // Apply computed expressions (always re-evaluate to ensure correctness,
            // since computed values are read-only on the frontend)
            if (config.getComputedExpression() != null
                    && !config.getComputedExpression().isBlank()) {
                String computed = evaluateExpression(config.getComputedExpression(), attributes);
                if (computed != null && !computed.isBlank()) {
                    attributes.put(config.getAttributeName(), List.of(computed));
                    continue;
                }
            }

            // Apply static default
            if (!hasValue && config.getDefaultValue() != null && !config.getDefaultValue().isBlank()) {
                attributes.put(config.getAttributeName(), List.of(config.getDefaultValue()));
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public String generatePassword(UUID profileId) {
        ProvisioningProfile profile = requireProfile(profileId);
        return passwordGenerator.generate(profile);
    }

    /** The LDAP attribute a generated password is written to. */
    static final String PASSWORD_ATTR = "userPassword";

    /**
     * For profiles whose {@link PasswordDisposition} has the server generate the
     * password ({@code GENERATED_*}), inject a freshly generated value into
     * {@code attributes} under {@value #PASSWORD_ATTR} when none was supplied,
     * and return the plaintext so the caller can deliver it
     * ({@code GENERATED_DELIVERED}) or drop it ({@code GENERATED_DISCARDED}).
     *
     * <p>No-op (returns {@code null}) for {@code OPERATOR_ENTERED}, or when a
     * non-blank password is already present (e.g. a re-submitted approval whose
     * payload still carries one). Must be called at create <em>execution</em>
     * time — never when queuing for approval — so the secret is not persisted in
     * the pending-approval payload.</p>
     */
    public String applyGeneratedPassword(ProvisioningProfile profile,
                                          Map<String, List<String>> attributes) {
        if (profile == null || !profile.getPasswordDisposition().isGenerated()) {
            return null;
        }
        List<String> existing = attributes.get(PASSWORD_ATTR);
        boolean hasValue = existing != null && !existing.isEmpty()
                && existing.get(0) != null && !existing.get(0).isBlank();
        if (hasValue) {
            return null;
        }
        String generated = passwordGenerator.generate(profile);
        attributes.put(PASSWORD_ATTR, List.of(generated));
        return generated;
    }

    @Transactional(readOnly = true)
    public ProvisioningProfile getEntity(UUID profileId) {
        return requireProfile(profileId);
    }

    /**
     * Fetch a profile entity, asserting it belongs to the given directory.
     * Used by directory-scoped callers (e.g. bulk import) that resolve a
     * caller-supplied {@code profileId} to its target OU / object classes.
     */
    @Transactional(readOnly = true)
    public ProvisioningProfile getEntityInDirectory(UUID directoryId, UUID profileId) {
        return requireProfileInDirectory(directoryId, profileId);
    }

    private ProfileResponse toResponse(ProvisioningProfile profile) {
        List<ProfileAttributeConfig> configs =
                attrConfigRepo.findAllByProfileIdOrderByDisplayOrderAsc(profile.getId());
        List<ProfileGroupAssignment> groups =
                groupAssignmentRepo.findAllByProfileIdOrderByDisplayOrderAsc(profile.getId());

        // Additional profiles
        List<ProfileResponse.AdditionalProfileEntry> additionalEntries = profile.getAdditionalProfiles()
                .stream()
                .map(p -> new ProfileResponse.AdditionalProfileEntry(p.getId(), p.getName()))
                .sorted(Comparator.comparing(ProfileResponse.AdditionalProfileEntry::name))
                .toList();

        // Effective group set: own + additional + auto-include (unless excluded)
        List<ProfileResponse.GroupAssignmentEntry> effectiveGroups = computeEffectiveGroups(profile, groups);

        return ProfileResponse.from(profile, configs, groups, additionalEntries, effectiveGroups);
    }

    private ProvisioningProfile requireProfile(UUID profileId) {
        return profileRepo.findById(profileId)
                .orElseThrow(() -> new ResourceNotFoundException("ProvisioningProfile", profileId));
    }

    private ProvisioningProfile requireProfileInDirectory(UUID directoryId, UUID profileId) {
        return profileRepo.findByIdAndDirectoryId(profileId, directoryId)
                .orElseThrow(() -> new ResourceNotFoundException("ProvisioningProfile", profileId));
    }

    private DirectoryConnection requireDirectory(UUID directoryId) {
        return dirRepo.findById(directoryId)
                .orElseThrow(() -> new ResourceNotFoundException("DirectoryConnection", directoryId));
    }

    /**
     * Trim a theme colour, collapsing blank to null so an "unset" profile stores
     * NULL rather than an empty string. The {@code #RRGGBB} shape is enforced by
     * the {@code @Pattern} on the request DTO; this only normalises whitespace.
     */
    private static String normalizeThemeColor(String themeColor) {
        return (themeColor != null && !themeColor.isBlank()) ? themeColor.trim() : null;
    }

    private void applyCommonFields(ProvisioningProfile profile, String name, String description,
                                    String targetUserDn, String targetGroupDn,
                                    List<String> objectClassNames,
                                    String rdnAttribute, boolean showDnField, String dnTemplate,
                                    Integer dnColumnSpan, String dnSectionName, Integer dnDisplayOrder,
                                    boolean enabled, boolean selfRegistrationAllowed,
                                    Integer passwordLength, Boolean passwordUppercase,
                                    Boolean passwordLowercase, Boolean passwordDigits,
                                    Boolean passwordSpecial, String passwordSpecialChars,
                                    Boolean emailPasswordToUser, String passwordDisposition) {
        // Operator-entered DN (showDnField) and self-registration are mutually
        // exclusive: self-registration composes the DN automatically from the RDN
        // attribute and can't have an operator typing one. Enforce it here so the
        // two can't drift regardless of entry point (UI, API, migration).
        if (showDnField && selfRegistrationAllowed) {
            throw new IllegalArgumentException(
                    "Self-registration requires an automatically-composed DN; turn off "
                    + "operator-entered DN or self-registration");
        }
        profile.setName(name);
        profile.setDescription(description);
        profile.setTargetUserDn(targetUserDn);
        // Groups may live in a subtree separate from users; when an admin
        // leaves it blank, default to the user DN so the column (NOT NULL)
        // is always populated and behaviour matches the historical
        // single-DN profiles (and the pre-existing-row backfill).
        profile.setTargetGroupDn(
                (targetGroupDn != null && !targetGroupDn.isBlank()) ? targetGroupDn : targetUserDn);
        profile.setObjectClassNames(new ArrayList<>(objectClassNames));
        profile.setRdnAttribute(rdnAttribute);
        profile.setShowDnField(showDnField);
        // Blank collapses to null so the create form falls back to the default
        // "<rdn>=<value>,<targetUserDn>" composition rather than an empty template.
        profile.setDnTemplate((dnTemplate != null && !dnTemplate.isBlank()) ? dnTemplate.trim() : null);
        // DN field layout — null reproduces the default (after the RDN, 2/3 width).
        profile.setDnColumnSpan(dnColumnSpan);
        profile.setDnSectionName((dnSectionName != null && !dnSectionName.isBlank()) ? dnSectionName.trim() : null);
        profile.setDnDisplayOrder(dnDisplayOrder);
        profile.setEnabled(enabled);
        profile.setSelfRegistrationAllowed(selfRegistrationAllowed);
        if (passwordLength != null)       profile.setPasswordLength(passwordLength);
        if (passwordUppercase != null)    profile.setPasswordUppercase(passwordUppercase);
        if (passwordLowercase != null)    profile.setPasswordLowercase(passwordLowercase);
        if (passwordDigits != null)       profile.setPasswordDigits(passwordDigits);
        if (passwordSpecial != null)      profile.setPasswordSpecial(passwordSpecial);
        if (passwordSpecialChars != null) profile.setPasswordSpecialChars(passwordSpecialChars);
        if (emailPasswordToUser != null)  profile.setEmailPasswordToUser(emailPasswordToUser);
        // Null/blank keeps the existing value (or the entity default
        // OPERATOR_ENTERED on a fresh profile); an unrecognised value is a
        // 400 rather than a silent fallback.
        if (passwordDisposition != null && !passwordDisposition.isBlank()) {
            try {
                profile.setPasswordDisposition(
                        PasswordDisposition.valueOf(passwordDisposition.trim()));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Unknown passwordDisposition: " + passwordDisposition);
            }
        }
    }

    /** True for the password field — the PASSWORD widget or the userPassword attribute. */
    private static boolean isPasswordAttribute(AttributeConfigEntry e) {
        return InputType.PASSWORD.name().equals(e.inputType())
                || PASSWORD_ATTR.equalsIgnoreCase(e.attributeName());
    }

    private void saveAttributeConfigs(ProvisioningProfile profile,
                                       List<AttributeConfigEntry> entries) {
        // Validations must run on the full request, including the empty
        // case — a profile with emailPasswordToUser=true and zero attribute
        // configs is itself a violation, and used to slip through because
        // the empty-entries early return was above this block.
        List<AttributeConfigEntry> safeEntries = entries != null ? entries : List.of();

        // Emailing the password (either the explicit flag, or the
        // GENERATED_DELIVERED disposition which delivers by email) needs a
        // 'mail' attribute to deliver to.
        boolean deliversByEmail = profile.isEmailPasswordToUser()
                || profile.getPasswordDisposition() == PasswordDisposition.GENERATED_DELIVERED;
        if (deliversByEmail) {
            boolean hasMailRequired = safeEntries.stream().anyMatch(
                    e -> e.attributeName().equalsIgnoreCase("mail") && e.requiredOnCreate());
            if (!hasMailRequired) {
                throw new IllegalArgumentException(
                        "Emailing the password to the user requires a 'mail' attribute marked as required");
            }
        }

        // In Automatic DN mode (showDnField == false) the entry's DN is
        // "<rdnAttribute>=<value>,<targetUserDn>", composed by the app, and that
        // value comes from the RDN attribute's own form field — so the RDN
        // attribute must be a configured field whose value is guaranteed at create
        // time: required, or derived (computed / HIDDEN_FIXED). Otherwise the
        // create form has no way to supply the RDN and the directory rejects the
        // add. In operator-entered mode (showDnField == true) the operator supplies
        // the DN directly (optionally via a template), so this doesn't apply; an
        // empty config list uses the fallback create form, which carries its own
        // RDN value field — both are exempt.
        if (!safeEntries.isEmpty()
                && !profile.isShowDnField()
                && profile.getRdnAttribute() != null && !profile.getRdnAttribute().isBlank()) {
            String rdn = profile.getRdnAttribute();
            AttributeConfigEntry rdnCfg = safeEntries.stream()
                    .filter(e -> e.attributeName().equalsIgnoreCase(rdn))
                    .findFirst().orElse(null);
            if (rdnCfg == null) {
                throw new IllegalArgumentException(
                        "The RDN attribute '" + rdn + "' must be a configured form attribute");
            }
            boolean derived = (rdnCfg.computedExpression() != null && !rdnCfg.computedExpression().isBlank())
                    || InputType.HIDDEN_FIXED.name().equals(rdnCfg.inputType());
            if (!rdnCfg.requiredOnCreate() && !derived) {
                throw new IllegalArgumentException(
                        "The RDN attribute '" + rdn + "' must be required or have a computed value");
            }
        }

        for (AttributeConfigEntry e : safeEntries) {
            // A required attribute may be hidden when the server supplies its
            // value at create time, so the operator never needs to see or enter
            // it: a password the profile auto-generates (GENERATED_*), or a
            // HIDDEN_FIXED attribute whose value the server applies from the
            // config (e.g. the always-hidden objectClass).
            boolean filledByGeneration = isPasswordAttribute(e)
                    && profile.getPasswordDisposition().isGenerated();
            boolean filledServerSide = filledByGeneration
                    || InputType.HIDDEN_FIXED.name().equals(e.inputType());
            if (e.requiredOnCreate() && e.hidden()
                    && (e.computedExpression() == null || e.computedExpression().isBlank())
                    && !filledServerSide) {
                throw new IllegalArgumentException(
                        "Required attribute '" + e.attributeName() + "' cannot be hidden unless it has a computed expression");
            }
            // Pre-compile validation regex to catch malformed patterns at
            // configuration time rather than each user-create call.
            if (e.validationRegex() != null && !e.validationRegex().isBlank()) {
                try {
                    Pattern.compile(e.validationRegex());
                } catch (PatternSyntaxException pse) {
                    throw new IllegalArgumentException(
                            "Invalid validation regex for attribute [" + e.attributeName()
                                    + "]: " + pse.getDescription());
                }
            }
        }

        if (safeEntries.isEmpty()) return;

        for (int i = 0; i < entries.size(); i++) {
            AttributeConfigEntry e = entries.get(i);
            ProfileAttributeConfig c = new ProfileAttributeConfig();
            c.setProfile(profile);
            c.setAttributeName(e.attributeName());
            c.setCustomLabel(e.customLabel());
            c.setInputType(InputType.valueOf(e.inputType()));
            c.setRequiredOnCreate(e.requiredOnCreate());
            c.setEditableOnCreate(e.editableOnCreate());
            c.setEditableOnUpdate(e.editableOnUpdate());
            c.setSelfServiceEdit(e.selfServiceEdit());
            c.setSelfRegistrationEdit(e.selfRegistrationEdit());
            c.setDefaultValue(e.defaultValue());
            c.setComputedExpression(e.computedExpression());
            c.setValidationRegex(e.validationRegex());
            c.setValidationMessage(e.validationMessage());
            c.setAllowedValues(e.allowedValues());
            c.setMinLength(e.minLength());
            c.setMaxLength(e.maxLength());
            c.setSectionName(e.sectionName());
            c.setColumnSpan(e.columnSpan() != null ? e.columnSpan() : 6);
            c.setDisplayOrder(i);
            c.setHidden(e.hidden());
            c.setRegistrationSectionName(e.registrationSectionName());
            c.setRegistrationColumnSpan(e.registrationColumnSpan());
            c.setRegistrationDisplayOrder(e.registrationColumnSpan() != null ? i : null);
            c.setSelfServiceSectionName(e.selfServiceSectionName());
            c.setSelfServiceColumnSpan(e.selfServiceColumnSpan());
            c.setSelfServiceDisplayOrder(e.selfServiceColumnSpan() != null ? i : null);
            attrConfigRepo.save(c);
        }
    }

    private void saveGroupAssignments(ProvisioningProfile profile,
                                       List<GroupAssignmentEntry> entries) {
        saveGroupAssignments(profile, entries, false);
    }

    private void saveGroupAssignments(ProvisioningProfile profile,
                                       List<GroupAssignmentEntry> entries,
                                       boolean validateMemberAttributes) {
        if (entries == null || entries.isEmpty()) return;

        DirectoryConnection dir = validateMemberAttributes
                ? dirRepo.findById(profile.getDirectory().getId()).orElse(null)
                : null;
        for (int i = 0; i < entries.size(); i++) {
            GroupAssignmentEntry e = entries.get(i);
            String memberAttribute = e.memberAttribute() != null ? e.memberAttribute() : "member";
            if (dir != null) {
                requireMemberAttributePermitted(dir, e.groupDn(), memberAttribute);
            }
            ProfileGroupAssignment g = new ProfileGroupAssignment();
            g.setProfile(profile);
            g.setGroupDn(e.groupDn());
            g.setMemberAttribute(memberAttribute);
            g.setDisplayOrder(i);
            groupAssignmentRepo.save(g);
        }
    }

    /**
     * Best-effort schema check for a profile group assignment: read the
     * group entry's objectClasses and verify the configured member
     * attribute is permitted by them — so a {@code groupOfUniqueNames}
     * group configured with {@code member} fails at profile save with a
     * 400 naming the right attribute, instead of silently failing every
     * subsequent user create (the per-group add failures there are
     * logged-and-continued by design).
     *
     * <p>Skips silently when the group entry or the server schema can't
     * be read — pre-staged groups (the {@code force=true} workflow) and
     * binds without subschema access must stay saveable; the LDAP server
     * remains authoritative at write time.</p>
     */
    private void requireMemberAttributePermitted(DirectoryConnection dir,
                                                 String groupDn,
                                                 String memberAttribute) {
        List<String> objectClasses;
        Set<String> permitted = new HashSet<>();
        try {
            LdapGroup group = ldapGroupService.getGroup(dir, groupDn, "objectClass");
            objectClasses = group.getValues("objectclass");
            if (objectClasses == null || objectClasses.isEmpty()) {
                return;
            }
            var schemaAttrs = ldapSchemaService.getAttributesForObjectClasses(dir, objectClasses);
            schemaAttrs.required().forEach(a -> permitted.add(a.toLowerCase()));
            schemaAttrs.optional().forEach(a -> permitted.add(a.toLowerCase()));
        } catch (Exception ex) {
            log.debug("Skipping member-attribute validation for group {}: {}",
                    groupDn, ex.getMessage());
            return;
        }
        if (permitted.isEmpty() || permitted.contains(memberAttribute.toLowerCase())) {
            return;
        }
        String suggestion = java.util.stream.Stream
                .of("member", "uniqueMember", "memberUid", "roleOccupant")
                .filter(c -> permitted.contains(c.toLowerCase()))
                .findFirst()
                .orElse(null);
        throw new IllegalArgumentException(
                "Group [" + groupDn + "] (objectClasses " + objectClasses
                        + ") does not allow member attribute [" + memberAttribute + "]"
                        + (suggestion != null
                            ? " — use [" + suggestion + "] instead."
                            : " — pick an attribute its objectClasses permit."));
    }

    private void saveAdditionalProfiles(ProvisioningProfile profile, List<UUID> additionalProfileIds) {
        Set<ProvisioningProfile> additionals = new HashSet<>();
        // Auto-include profiles must not have additional profiles to prevent cascading group membership
        if (additionalProfileIds != null && !profile.isAutoIncludeGroups()) {
            for (UUID apId : additionalProfileIds) {
                if (apId.equals(profile.getId())) continue; // skip self-reference
                ProvisioningProfile ap = profileRepo.findById(apId)
                        .orElseThrow(() -> new ResourceNotFoundException("ProvisioningProfile", apId));
                additionals.add(ap);
            }
        }
        profile.setAdditionalProfiles(additionals);
        profileRepo.save(profile);
    }

    // ── Effective group computation ──────────────────────────────────────────

    /**
     * Computes the effective group set for a profile: own groups + additional
     * profiles' groups + auto-include profiles' groups (unless excluded).
     */
    private List<ProfileResponse.GroupAssignmentEntry> computeEffectiveGroups(
            ProvisioningProfile profile,
            List<ProfileGroupAssignment> ownGroups) {

        // Use groupDn as dedup key, preserving first occurrence
        Map<String, ProfileResponse.GroupAssignmentEntry> seen = new LinkedHashMap<>();

        // 1. Own groups
        for (ProfileGroupAssignment g : ownGroups) {
            seen.putIfAbsent(g.getGroupDn(),
                    ProfileResponse.GroupAssignmentEntry.from(g));
        }

        // 2. Explicit additional profiles
        for (ProvisioningProfile ap : profile.getAdditionalProfiles()) {
            for (ProfileGroupAssignment g :
                    groupAssignmentRepo.findAllByProfileIdOrderByDisplayOrderAsc(ap.getId())) {
                seen.putIfAbsent(g.getGroupDn(),
                        ProfileResponse.GroupAssignmentEntry.from(g));
            }
        }

        // 3. Auto-include profiles (unless this profile opts out)
        if (!profile.isExcludeAutoIncludes()) {
            List<ProvisioningProfile> autoIncludes =
                    profileRepo.findAllByDirectoryIdAndAutoIncludeGroupsTrue(
                            profile.getDirectory().getId());
            for (ProvisioningProfile ai : autoIncludes) {
                if (ai.getId().equals(profile.getId())) continue; // skip self
                for (ProfileGroupAssignment g :
                        groupAssignmentRepo.findAllByProfileIdOrderByDisplayOrderAsc(ai.getId())) {
                    seen.putIfAbsent(g.getGroupDn(),
                            ProfileResponse.GroupAssignmentEntry.from(g));
                }
            }
        }

        return List.copyOf(seen.values());
    }

    // ── Group change evaluation and application ──────────────────────────────

    /**
     * Evaluates group membership changes for all users provisioned under this
     * profile by comparing their current group memberships against the
     * effective group set.
     */
    @Transactional(readOnly = true)
    public GroupChangePreview evaluateGroupChanges(UUID directoryId, UUID profileId) {
        ProvisioningProfile profile = requireProfileInDirectory(directoryId, profileId);
        DirectoryConnection dc = requireDirectory(directoryId);

        List<ProfileGroupAssignment> ownGroups =
                groupAssignmentRepo.findAllByProfileIdOrderByDisplayOrderAsc(profileId);
        List<ProfileResponse.GroupAssignmentEntry> effective =
                computeEffectiveGroups(profile, ownGroups);

        // Build a map of groupDn -> memberAttribute for the effective set
        Map<String, String> effectiveMap = new LinkedHashMap<>();
        for (ProfileResponse.GroupAssignmentEntry g : effective) {
            effectiveMap.put(g.groupDn(), g.memberAttribute());
        }

        // Search LDAP for users under the profile's target OU
        List<LdapUser> users = ldapUserService.searchUsers(
                dc, "(objectClass=*)", profile.getTargetUserDn(), "dn");

        List<GroupChangePreview.UserGroupChange> changes = new ArrayList<>();

        for (LdapUser user : users) {
            String userDn = user.getDn();
            List<String> currentMemberships = user.getMemberOf();
            Set<String> currentSet = currentMemberships.stream()
                    .map(String::toLowerCase)
                    .collect(Collectors.toSet());

            List<GroupChangePreview.GroupChange> toAdd = new ArrayList<>();
            List<GroupChangePreview.GroupChange> toRemove = new ArrayList<>();

            // Groups to add: in effective set but user is not a member
            for (var entry : effectiveMap.entrySet()) {
                if (!currentSet.contains(entry.getKey().toLowerCase())) {
                    toAdd.add(new GroupChangePreview.GroupChange(
                            entry.getKey(), entry.getValue()));
                }
            }

            // We only add groups, never remove — removing would require tracking
            // which groups were originally assigned by profiles vs manually added.
            // Future enhancement: track profile-managed group memberships.

            if (!toAdd.isEmpty()) {
                changes.add(new GroupChangePreview.UserGroupChange(
                        userDn, toAdd, toRemove));
            }
        }

        return new GroupChangePreview(changes, changes.size());
    }

    /**
     * Applies the group membership changes previewed by
     * {@link #evaluateGroupChanges(UUID, UUID)}.
     */
    @Transactional
    public GroupChangePreview applyGroupChanges(UUID directoryId, UUID profileId,
                                                 AuthPrincipal principal) {
        GroupChangePreview preview = evaluateGroupChanges(directoryId, profileId);
        DirectoryConnection dc = requireDirectory(directoryId);

        for (GroupChangePreview.UserGroupChange change : preview.changes()) {
            for (GroupChangePreview.GroupChange add : change.groupsToAdd()) {
                try {
                    ldapGroupService.addMember(dc, add.groupDn(),
                            add.memberAttribute(), change.userDn());
                    auditService.record(principal, directoryId,
                            AuditAction.GROUP_MEMBER_ADD, add.groupDn(),
                            Map.of("attribute", add.memberAttribute(),
                                    "member", change.userDn(),
                                    "source", "additional_profiles"));
                } catch (Exception e) {
                    log.warn("Failed to add {} to group {}: {}",
                            change.userDn(), add.groupDn(), e.getMessage());
                }
            }
            for (GroupChangePreview.GroupChange remove : change.groupsToRemove()) {
                try {
                    ldapGroupService.removeMember(dc, remove.groupDn(),
                            remove.memberAttribute(), change.userDn());
                    auditService.record(principal, directoryId,
                            AuditAction.GROUP_MEMBER_REMOVE, remove.groupDn(),
                            Map.of("attribute", remove.memberAttribute(),
                                    "member", change.userDn(),
                                    "source", "additional_profiles"));
                } catch (Exception e) {
                    log.warn("Failed to remove {} from group {}: {}",
                            change.userDn(), remove.groupDn(), e.getMessage());
                }
            }
        }

        return preview;
    }

    /**
     * Applies only the selected group membership changes.
     *
     * <p>Validates every entry up-front against the directory's profile
     * universe before touching LDAP: the {@code groupDn} must be configured
     * on some profile in this directory (with a matching member attribute),
     * and the {@code userDn} must live under some profile's target OU. This
     * prevents a buggy or hostile frontend from using this endpoint as a
     * generic group-membership writer to arbitrary groups or DNs.</p>
     *
     * <p>Validation is strict — if any entry is invalid, the whole request
     * is rejected with a 400 and no LDAP writes occur. This matches the
     * intent of the companion "evaluate" endpoint: callers should apply only
     * changes that came out of evaluate.</p>
     */
    @Transactional
    public int applySelectiveGroupChanges(UUID directoryId,
                                           SelectiveGroupChangeRequest request,
                                           AuthPrincipal principal) {
        DirectoryConnection dc = requireDirectory(directoryId);

        validateSelectiveGroupChangeEntries(directoryId, request);

        int applied = 0;
        for (SelectiveGroupChangeRequest.GroupMembershipEntry entry : request.entries()) {
            try {
                ldapGroupService.addMember(dc, entry.groupDn(),
                        entry.memberAttribute(), entry.userDn());
                auditService.record(principal, directoryId,
                        AuditAction.GROUP_MEMBER_ADD, entry.groupDn(),
                        Map.of("attribute", entry.memberAttribute(),
                                "member", entry.userDn(),
                                "source", "compliance_check"));
                applied++;
            } catch (Exception e) {
                log.warn("Failed to add {} to group {}: {}",
                        entry.userDn(), entry.groupDn(), e.getMessage());
            }
        }
        return applied;
    }

    /**
     * Removes {@code userDn} from every group in the profile's effective
     * Rejects any selective-group-change entry whose target group or user DN
     * doesn't belong to this directory's profile universe.
     */
    private void validateSelectiveGroupChangeEntries(UUID directoryId,
                                                      SelectiveGroupChangeRequest request) {
        if (request == null || request.entries() == null || request.entries().isEmpty()) {
            return;
        }

        List<ProvisioningProfile> profiles =
                profileRepo.findAllByDirectoryIdOrderByNameAsc(directoryId);

        // groupDn (lowercase) → set of acceptable memberAttribute values
        Map<String, Set<String>> allowedGroups = new HashMap<>();
        // Lowercase target-OU suffixes for userDn containment checks
        List<String> targetOus = new ArrayList<>();
        for (ProvisioningProfile p : profiles) {
            if (p.getTargetUserDn() != null && !p.getTargetUserDn().isBlank()) {
                targetOus.add(p.getTargetUserDn().toLowerCase());
            }
            for (ProfileGroupAssignment g :
                    groupAssignmentRepo.findAllByProfileIdOrderByDisplayOrderAsc(p.getId())) {
                allowedGroups
                        .computeIfAbsent(g.getGroupDn().toLowerCase(), k -> new HashSet<>())
                        .add(g.getMemberAttribute() != null ? g.getMemberAttribute() : "member");
            }
        }

        for (SelectiveGroupChangeRequest.GroupMembershipEntry entry : request.entries()) {
            if (entry.groupDn() == null || entry.userDn() == null
                    || entry.memberAttribute() == null) {
                throw new IllegalArgumentException(
                        "Group membership entry is missing required fields");
            }
            String groupKey = entry.groupDn().toLowerCase();
            Set<String> attrs = allowedGroups.get(groupKey);
            if (attrs == null) {
                throw new IllegalArgumentException(
                        "Group [" + entry.groupDn() + "] is not assigned by any profile in this directory");
            }
            if (!attrs.contains(entry.memberAttribute())) {
                throw new IllegalArgumentException(
                        "Member attribute [" + entry.memberAttribute()
                                + "] does not match the profile configuration for ["
                                + entry.groupDn() + "]");
            }
            String userDnLower = entry.userDn().toLowerCase();
            boolean underAnyOu = targetOus.stream().anyMatch(userDnLower::endsWith);
            if (!underAnyOu) {
                throw new IllegalArgumentException(
                        "User [" + entry.userDn() + "] is not under any profile target OU in this directory");
            }
        }
    }

    /**
     * Adds {@code userDn} to every group in the profile's effective
     * group set (own groups + additional profiles' groups + auto-include
     * profiles' groups, unless excluded). Called by user-delete paths so
     * that group memberships granted by this profile are cleaned up when
     * the user entry leaves LDAP.
     *
     * <p>Failures on individual group removes are logged at {@code DEBUG}
     * (not {@code WARN}): many entries won't be members of every group in
     * the effective set, and most LDAP servers raise
     * {@code NO_SUCH_ATTRIBUTE} / {@code NO_SUCH_OBJECT} in that case. Each
     * successful remove emits a {@code GROUP_MEMBER_REMOVE} audit row with
     * {@code source = "profile_delete"} so reports can distinguish
     * provisioning-driven removals from operator-initiated ones.</p>
     */
    @Transactional
    public int removeUserFromProfileGroups(UUID directoryId, UUID profileId,
                                            String userDn, AuthPrincipal principal) {
        return removeUserFromProfileGroups(directoryId, profileId, userDn, principal, "profile_delete");
    }

    /**
     * Variant that stamps a caller-supplied {@code source} discriminator on the
     * {@code GROUP_MEMBER_REMOVE} audit rows — {@code "profile_delete"} for a
     * delete cleanup, {@code "profile_move"} when shedding the old profile's
     * groups as a user is moved into another profile.
     */
    @Transactional
    public int removeUserFromProfileGroups(UUID directoryId, UUID profileId,
                                            String userDn, AuthPrincipal principal, String source) {
        ProvisioningProfile profile = requireProfileInDirectory(directoryId, profileId);
        DirectoryConnection dc = requireDirectory(directoryId);

        List<ProfileGroupAssignment> ownGroups =
                groupAssignmentRepo.findAllByProfileIdOrderByDisplayOrderAsc(profileId);
        List<ProfileResponse.GroupAssignmentEntry> effective =
                computeEffectiveGroups(profile, ownGroups);

        int removed = 0;
        for (ProfileResponse.GroupAssignmentEntry g : effective) {
            try {
                ldapGroupService.removeMember(dc, g.groupDn(), g.memberAttribute(), userDn);
                auditService.record(principal, directoryId,
                        AuditAction.GROUP_MEMBER_REMOVE, g.groupDn(),
                        Map.of("attribute", g.memberAttribute(),
                                "member", userDn,
                                "source", source));
                removed++;
            } catch (Exception e) {
                // Debug not warn: many entries won't be members of every
                // group in the effective set, and most LDAP servers raise
                // NO_SUCH_ATTRIBUTE / NO_SUCH_OBJECT in that case.
                log.debug("Skipping group {} during {} cleanup for {}: {}",
                        g.groupDn(), source, userDn, e.getMessage());
            }
        }
        return removed;
    }

    /**
     * Adds {@code userDn} to every group in the profile's effective group
     * set (own groups + additional profiles' groups + auto-include profiles'
     * groups, unless excluded). Called by user-create paths so that group
     * assignments declared on the profile are applied immediately after the
     * user entry lands in LDAP — previously this was done client-side by
     * the UI (and not at all by the bulk-import / approval-approved / API
     * flows).
     *
     * <p>Failures on individual group adds do not abort the call; the user
     * still exists and other group adds still run. Each failure is logged
     * AND returned as a per-group warning so callers with a response
     * channel (the create endpoint) can surface it to the operator —
     * a silently-skipped membership looks identical to a successful one
     * otherwise. Each successful add emits a {@code GROUP_MEMBER_ADD}
     * audit row with {@code source = "profile_create"} so reports can
     * distinguish provisioning-driven additions from operator-initiated
     * ones.</p>
     */
    @Transactional
    public GroupAssignmentResult applyGroupAssignmentsToUser(UUID directoryId, UUID profileId,
                                            String userDn, AuthPrincipal principal) {
        return applyGroupAssignmentsToUser(directoryId, profileId, userDn, principal, "profile_create");
    }

    /**
     * Variant that stamps a caller-supplied {@code source} on the
     * {@code GROUP_MEMBER_ADD} audit rows — {@code "profile_create"} on user
     * creation, {@code "profile_move"} when applying the destination profile's
     * groups as a user is moved into it.
     */
    @Transactional
    public GroupAssignmentResult applyGroupAssignmentsToUser(UUID directoryId, UUID profileId,
                                            String userDn, AuthPrincipal principal, String source) {
        ProvisioningProfile profile = requireProfileInDirectory(directoryId, profileId);
        DirectoryConnection dc = requireDirectory(directoryId);

        List<ProfileGroupAssignment> ownGroups =
                groupAssignmentRepo.findAllByProfileIdOrderByDisplayOrderAsc(profileId);
        List<ProfileResponse.GroupAssignmentEntry> effective =
                computeEffectiveGroups(profile, ownGroups);

        int added = 0;
        List<String> warnings = new ArrayList<>();
        for (ProfileResponse.GroupAssignmentEntry g : effective) {
            try {
                ldapGroupService.addMember(dc, g.groupDn(), g.memberAttribute(), userDn);
                auditService.record(principal, directoryId,
                        AuditAction.GROUP_MEMBER_ADD, g.groupDn(),
                        Map.of("attribute", g.memberAttribute(),
                                "member", userDn,
                                "source", source));
                added++;
            } catch (Exception e) {
                log.warn("Failed to add {} to profile group {} ({}): {}",
                        userDn, g.groupDn(), source, e.getMessage());
                warnings.add("Not added to " + g.groupDn() + ": " + e.getMessage());
            }
        }
        return new GroupAssignmentResult(added, warnings);
    }

    /**
     * Outcome of {@link #applyGroupAssignmentsToUser}: how many of the
     * profile's effective groups the user was added to, plus one warning
     * per group that failed (already logged; returned so the create
     * response can carry them to the operator).
     */
    public record GroupAssignmentResult(int added, List<String> warnings) {}

    // ── Target-OU probe + validation ────────────────────────────────

    /**
     * Probe result for the target-OU verification — used by the
     * profile editor's warning banner. {@code exists=true} means the
     * OU is reachable via the directory's bind credentials and
     * resolves to an entry; {@code false} means either the OU isn't
     * there or the bind can't see it (same observable effect — user
     * creation will fail at the LDAP layer).
     */
    public record TargetOuProbeResult(boolean exists, String dn) {}

    @Transactional(readOnly = true)
    public TargetOuProbeResult probeTargetOu(UUID directoryId, String dn) {
        DirectoryConnection dir = requireDirectory(directoryId);
        boolean exists = ldapBrowseService.entryExists(dir, dn);
        return new TargetOuProbeResult(exists, dn);
    }

    /**
     * Refuses with a clear 400 message when the target OU doesn't
     * exist in the directory. Save callers gate on this only when
     * {@code force=false} — the {@code force=true} path is for the
     * legitimate pre-stage workflow (creating a profile before the
     * OU is provisioned in LDAP).
     */
    private void requireTargetOuExists(DirectoryConnection dir, String dn) {
        if (!ldapBrowseService.entryExists(dir, dn)) {
            throw new IllegalArgumentException(
                    "Target User DN [" + dn + "] does not exist in directory ["
                            + dir.getDisplayName() + "]. Create the OU first, or "
                            + "pass force=true to save the profile anyway "
                            + "(user creation will fail until the OU exists).");
        }
    }

    // ── Seed attribute defaults ─────────────────────────────────────

    /**
     * Populates a profile's {@link ProfileAttributeConfig} list with a
     * curated set of defaults for a known schema. Mostly cuts the
     * "create a profile, then hand-author 20 attribute configs before
     * the create-user form is usable" cliff for new admins.
     *
     * <p>Refuses if the profile already has attribute configs — would
     * silently overwrite a deliberately-empty config (the admin might
     * want exactly that) or a partially-authored one. Caller can clear
     * the configs first via update if a re-seed is intended.</p>
     *
     * <p>Schema selector is a string so additional schemas
     * ({@code posixAccount}, AD's {@code user}, etc) can be added
     * without changing the API shape. Unknown schema → 400.</p>
     */
    @Transactional
    public ProfileResponse seedAttributeDefaults(UUID directoryId,
                                                  UUID profileId,
                                                  String schema,
                                                  AuthPrincipal principal) {
        ProvisioningProfile profile = profileRepo.findByIdAndDirectoryId(profileId, directoryId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Profile [" + profileId + "] not found in directory [" + directoryId + "]"));

        long existing = attrConfigRepo.countByProfileId(profileId);
        if (existing > 0) {
            throw new ConflictException(
                    "Profile already has " + existing + " attribute config(s) — "
                            + "clear them first if you really want to re-seed defaults");
        }

        List<ProfileAttributeConfig> seeds = switch (schema == null ? "" : schema.toLowerCase()) {
            case "inetorgperson" -> inetOrgPersonDefaults(profile);
            default -> throw new IllegalArgumentException(
                    "Unsupported schema [" + schema + "] — supported: inetOrgPerson");
        };

        // The seed list is a curated superset — filter it against the live
        // directory schema so a row the profile's objectClass chain doesn't
        // permit (e.g. 'c'/countryName on standard inetOrgPerson) isn't
        // seeded only for the directory to reject every entry that sets it.
        // Mirrors the client-side seed filter in SuperadminProfilesView.
        List<String> skipped = new ArrayList<>();
        Set<String> permitted = permittedSchemaAttrs(profile);
        if (permitted != null) {
            List<ProfileAttributeConfig> kept = new ArrayList<>();
            for (ProfileAttributeConfig c : seeds) {
                if (permitted.contains(c.getAttributeName().toLowerCase(Locale.ROOT))) {
                    kept.add(c);
                } else {
                    skipped.add(c.getAttributeName());
                }
            }
            seeds = kept;
        }

        for (ProfileAttributeConfig c : seeds) {
            attrConfigRepo.save(c);
        }

        if (principal != null) {
            auditService.recordSystemEvent(principal, AuditAction.PROFILE_UPDATE,
                    Map.of("profileId", profileId,
                            "action", "seed_attribute_defaults",
                            "schema", schema,
                            "count", seeds.size(),
                            "skippedNotInSchema", skipped));
        }

        return get(directoryId, profileId);
    }

    /**
     * The inetOrgPerson seed set. Sections + column widths + required
     * flags chosen to match the layout conventions described in
     * docs/frontend-conventions.md:
     * <ul>
     *   <li>Identity / Contact / Organization / Account sections</li>
     *   <li>{@code columnSpan} 2 for short fields (employeeNumber,
     *       l/st/c, phone numbers), 3 for medium (default), 6 for long
     *       (postalAddress, description, mail, displayName, manager)</li>
     *   <li>{@code requiredOnCreate} for inetOrgPerson MUST attrs
     *       (cn, sn)</li>
     *   <li>{@code editableOnUpdate=false} for {@code uid} — it's the
     *       RDN; a value change is a MODRDN, not an in-place edit</li>
     * </ul>
     */
    /**
     * Lower-cased union of required + optional attribute names the live
     * directory schema permits for the profile's objectClasses, or
     * {@code null} when the schema can't be consulted (directory unreachable,
     * Entra ID, no objectClasses configured) — callers must treat null as
     * "skip filtering", never "nothing permitted".
     */
    private Set<String> permittedSchemaAttrs(ProvisioningProfile profile) {
        if (profile.getObjectClassNames().isEmpty()
                || profile.getDirectory().getDirectoryType() == DirectoryType.ENTRA_ID) {
            return null;
        }
        try {
            var attrs = ldapSchemaService.getAttributesForObjectClasses(
                    profile.getDirectory(), profile.getObjectClassNames());
            Set<String> permitted = new HashSet<>();
            attrs.required().forEach(a -> permitted.add(a.toLowerCase(Locale.ROOT)));
            attrs.optional().forEach(a -> permitted.add(a.toLowerCase(Locale.ROOT)));
            return permitted.isEmpty() ? null : permitted;
        } catch (Exception e) {
            log.warn("Schema lookup failed for profile [{}] — seeding without schema filter: {}",
                    profile.getId(), e.getMessage());
            return null;
        }
    }

    private List<ProfileAttributeConfig> inetOrgPersonDefaults(ProvisioningProfile profile) {
        List<ProfileAttributeConfig> out = new ArrayList<>();
        int order = 0;
        // ── Identity ─────────────────────────────────────────────────
        out.add(seed(profile, "uid",              "Identity", ++order, 3, InputType.TEXT,     true,  true,  false));
        out.add(seed(profile, "cn",               "Identity", ++order, 3, InputType.TEXT,     true,  true,  true));
        out.add(seed(profile, "givenName",        "Identity", ++order, 3, InputType.TEXT,     false, true,  true));
        out.add(seed(profile, "sn",               "Identity", ++order, 3, InputType.TEXT,     true,  true,  true));
        out.add(seed(profile, "displayName",      "Identity", ++order, 6, InputType.TEXT,     false, true,  true));
        out.add(seed(profile, "initials",         "Identity", ++order, 2, InputType.TEXT,     false, true,  true));
        out.add(seed(profile, "employeeNumber",   "Identity", ++order, 2, InputType.TEXT,     false, true,  true));
        out.add(seed(profile, "employeeType",     "Identity", ++order, 2, InputType.TEXT,     false, true,  true));
        // ── Contact ──────────────────────────────────────────────────
        out.add(seed(profile, "mail",                    "Contact", ++order, 6, InputType.TEXT,     false, true, true));
        out.add(seed(profile, "telephoneNumber",         "Contact", ++order, 2, InputType.TEXT,     false, true, true));
        out.add(seed(profile, "mobile",                  "Contact", ++order, 2, InputType.TEXT,     false, true, true));
        out.add(seed(profile, "pager",                   "Contact", ++order, 2, InputType.TEXT,     false, true, true));
        out.add(seed(profile, "facsimileTelephoneNumber","Contact", ++order, 2, InputType.TEXT,     false, true, true));
        out.add(seed(profile, "homePhone",               "Contact", ++order, 2, InputType.TEXT,     false, true, true));
        out.add(seed(profile, "postalAddress",           "Contact", ++order, 6, InputType.TEXTAREA, false, true, true));
        out.add(seed(profile, "street",                  "Contact", ++order, 6, InputType.TEXT,     false, true, true));
        out.add(seed(profile, "l",                       "Contact", ++order, 2, InputType.TEXT,     false, true, true));
        out.add(seed(profile, "st",                      "Contact", ++order, 2, InputType.TEXT,     false, true, true));
        out.add(seed(profile, "c",                       "Contact", ++order, 2, InputType.TEXT,     false, true, true));
        out.add(seed(profile, "postalCode",              "Contact", ++order, 2, InputType.TEXT,     false, true, true));
        // ── Organization ─────────────────────────────────────────────
        out.add(seed(profile, "title",            "Organization", ++order, 3, InputType.TEXT,     false, true, true));
        out.add(seed(profile, "ou",               "Organization", ++order, 3, InputType.TEXT,     false, true, true));
        out.add(seed(profile, "o",                "Organization", ++order, 3, InputType.TEXT,     false, true, true));
        out.add(seed(profile, "departmentNumber", "Organization", ++order, 3, InputType.TEXT,     false, true, true));
        out.add(seed(profile, "manager",          "Organization", ++order, 6, InputType.DN_LOOKUP,false, true, true));
        out.add(seed(profile, "description",      "Organization", ++order, 6, InputType.TEXTAREA, false, true, true));
        // ── Account ──────────────────────────────────────────────────
        out.add(seed(profile, "userPassword",     "Account",      ++order, 6, InputType.PASSWORD, true, true, false));
        return out;
    }

    private ProfileAttributeConfig seed(ProvisioningProfile profile,
                                         String name,
                                         String section,
                                         int order,
                                         int span,
                                         InputType type,
                                         boolean required,
                                         boolean editableCreate,
                                         boolean editableUpdate) {
        ProfileAttributeConfig c = new ProfileAttributeConfig();
        c.setProfile(profile);
        c.setAttributeName(name);
        c.setSectionName(section);
        c.setDisplayOrder(order);
        c.setColumnSpan(span);
        c.setInputType(type);
        c.setRequiredOnCreate(required);
        c.setEditableOnCreate(editableCreate);
        c.setEditableOnUpdate(editableUpdate);
        return c;
    }
}
