// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva;

import com.ldapportal.addons.isva.entity.IsvaDemographicDeleteMode;
import com.ldapportal.addons.isva.entity.IsvaDeletePolicy;
import com.ldapportal.addons.isva.entity.IsvaGroupMemberTarget;
import com.ldapportal.addons.isva.entity.IsvaTopologyMode;
import com.ldapportal.addons.isva.entity.VendorIntegrationIsvaConfig;
import com.ldapportal.addons.isva.repository.VendorIntegrationIsvaConfigRepository;
import com.ldapportal.addons.isva.service.IsvaProfileOverrideService;
import com.ldapportal.core.provisioning.AddStep;
import com.ldapportal.core.provisioning.BaselinePlans;
import com.ldapportal.core.provisioning.DeletePlan;
import com.ldapportal.core.provisioning.DeleteStep;
import com.ldapportal.core.provisioning.GroupMemberPlan;
import com.ldapportal.core.provisioning.LdapOperationStep;
import com.ldapportal.core.provisioning.ModifyStep;
import com.ldapportal.core.provisioning.PasswordPlan;
import com.ldapportal.core.provisioning.PasswordSetPayload;
import com.ldapportal.core.provisioning.ProvisioningContext;
import com.ldapportal.core.provisioning.ProvisioningInterceptor;
import com.ldapportal.core.provisioning.ProvisioningRefusedException;
import com.ldapportal.core.provisioning.UserCreatePayload;
import com.ldapportal.core.provisioning.UserCreatePlan;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.EnableDisableValueType;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The ISVA-aware {@link ProvisioningInterceptor}. Looks up the
 * per-directory config; for directories that haven't enabled the
 * addon, delegates to {@link BaselinePlans} so the LDAP traffic
 * stays byte-identical to pre-SPI behaviour.
 *
 * <p>Two topology modes, both implemented:</p>
 *
 * <p><b>Inline</b> — one LDAP entry per user carrying both
 * demographic and {@code sec*} attributes. Plans are single-step.</p>
 *
 * <p><b>Linked</b> — two LDAP entries per user in different DITs.
 * The interceptor uses {@link IsvaLinkedUserLookup} to resolve the
 * paired secUser DN at plan time, then builds multi-step plans
 * that write against both. Linked-mode user creation uses the
 * COMPENSATE step-failure policy on the secUser ADD so
 * a failure there triggers a best-effort delete of the
 * just-created demographic entry — preventing orphan demographics
 * from accumulating. The secUser grant / revoke plan fragments live in
 * {@link IsvaSecUserPlans}; this interceptor composes them for the
 * user-lifecycle paths.</p>
 *
 * <p>Orphan handling for linked-mode operations whose lookup
 * returns empty:</p>
 * <ul>
 *   <li><b>delete (DISABLE)</b> — refuse with a clear message.
 *       Nothing to disable; the demographic is orphaned. Operator
 *       can hard-delete instead.</li>
 *   <li><b>delete (HARD_DELETE)</b> — proceed with only the
 *       demographic DEL. Orphan demographics are the case
 *       hard-delete is most useful for.</li>
 *   <li><b>password set</b> — refuse. Without a secUser to stamp,
 *       ISVA's password-rotation logic would silently disagree
 *       with the actual change.</li>
 *   <li><b>group membership (SECUSER_DN)</b> — refuse. Without a
 *       secUser DN, the membership would have to fall back to the
 *       demographic DN, but ISVA would then ignore the entry —
 *       silent wrong behaviour is worse than a loud refusal.</li>
 *   <li><b>group membership (DEMOGRAPHIC_DN)</b> — no lookup
 *       needed; proceed as inline.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IsvaProvisioningInterceptor implements ProvisioningInterceptor {

    private final VendorIntegrationIsvaConfigRepository configRepo;
    private final IsvaLinkedUserLookup linkedUserLookup;
    private final IsvaProfileOverrideService overrideService;
    private final IsvaSecUserPlans secUserPlans;
    private final IsvaGroupShapeCheck groupShapeCheck;

    // ── user create ──────────────────────────────────────────────────

    @Override
    public UserCreatePlan planUserCreate(DirectoryConnection dir,
                                         UserCreatePayload payload,
                                         ProvisioningContext ctx) {
        VendorIntegrationIsvaConfig cfg = activeConfigOrNull(dir);
        if (cfg == null || isExempt(ctx)) {
            return BaselinePlans.userCreate(payload);
        }

        return switch (cfg.getTopologyMode()) {
            case INLINE -> planInlineUserCreate(cfg, payload);
            case LINKED -> planLinkedUserCreate(cfg, payload);
        };
    }

    private UserCreatePlan planInlineUserCreate(VendorIntegrationIsvaConfig cfg,
                                                  UserCreatePayload payload) {
        List<Attribute> attrs = secUserPlans.grantInline(
                BaselinePlans.attributesFromMap(payload.attributes()), cfg, payload);
        return UserCreatePlan.singleStep(AddStep.of(payload.dn(), attrs));
    }

    private UserCreatePlan planLinkedUserCreate(VendorIntegrationIsvaConfig cfg,
                                                  UserCreatePayload payload) {
        // Step 1: ADD the demographic entry exactly as the operator
        // specified — no sec* attributes folded in. The demographic
        // side has no awareness of ISVA in linked-mode shops.
        List<Attribute> demographicAttrs = BaselinePlans.attributesFromMap(payload.attributes());
        AddStep step1 = AddStep.of(payload.dn(), demographicAttrs);

        // Step 2: the secUser grant — ADD the paired secUser entry
        // under the management DIT (COMPENSATE so a failure rolls back
        // the demographic entry step 1 just created).
        AddStep step2 = secUserPlans.grantLinked(cfg, payload);

        // Compensation: DEL the demographic entry that step 1 added.
        // Best-effort; if it fails (something else has already
        // referenced the demographic, for example) the original
        // step-2 exception still propagates and the executor logs
        // the compensation failure with a "manual repair" hint.
        List<LdapOperationStep> compensation = List.of(DeleteStep.of(payload.dn()));

        return new UserCreatePlan(List.of(step1, step2), Optional.of(compensation));
    }

    // ── user delete ──────────────────────────────────────────────────

    @Override
    public DeletePlan planUserDelete(DirectoryConnection dir,
                                     String demographicDn,
                                     ProvisioningContext ctx) {
        VendorIntegrationIsvaConfig cfg = activeConfigOrNull(dir);
        if (cfg == null || isExempt(ctx)) {
            return BaselinePlans.userDelete(demographicDn);
        }

        return switch (cfg.getTopologyMode()) {
            case INLINE -> planInlineUserDelete(cfg, demographicDn);
            case LINKED -> planLinkedUserDelete(dir, cfg, demographicDn);
        };
    }

    private DeletePlan planInlineUserDelete(VendorIntegrationIsvaConfig cfg, String dn) {
        if (cfg.getDeletePolicy() == IsvaDeletePolicy.HARD_DELETE) {
            return DeletePlan.singleStep(DeleteStep.of(dn));
        }
        // Inline mode: the secUser IS the demographic entry, so the
        // revoke MODIFY targets the same DN.
        return DeletePlan.singleStep(secUserPlans.disable(dn));
    }

    private DeletePlan planLinkedUserDelete(DirectoryConnection dir,
                                              VendorIntegrationIsvaConfig cfg,
                                              String demographicDn) {
        Optional<String> secUserDn = linkedUserLookup.findSecUserDn(
                dir, cfg.getManagementDitBaseDn(), demographicDn);

        if (cfg.getDeletePolicy() == IsvaDeletePolicy.HARD_DELETE) {
            // DEL secUser FIRST so a step-2 failure (couldn't delete
            // demographic) leaves a recoverable secUser-gone +
            // demographic-still-present state. Reverse order would
            // leave an orphaned secUser.
            //
            // Orphan demographic with no secUser: only the
            // demographic DEL.
            List<LdapOperationStep> steps = new ArrayList<>();
            secUserDn.ifPresent(s -> steps.add(secUserPlans.hardDelete(s)));
            steps.add(DeleteStep.of(demographicDn));
            return new DeletePlan(steps);
        }

        // DISABLE: refuse if there's no secUser to disable. Hard-
        // delete is the operator's escape hatch for orphan
        // demographics.
        if (secUserDn.isEmpty()) {
            throw new ProvisioningRefusedException(
                    "No linked secUser entry found for demographic DN " + demographicDn
                            + " under " + cfg.getManagementDitBaseDn()
                            + ". This is an orphaned demographic — use hard-delete to remove "
                            + "the demographic entry, or run pdadmin user import to repair.");
        }

        List<LdapOperationStep> steps = new ArrayList<>();
        steps.add(secUserPlans.disable(secUserDn.get()));

        // DISABLE_AND_MARK: also annotate the demographic entry, reusing the
        // directory's own configured enable/disable attribute as the marker
        // (e.g. AD userAccountControl=514, nsAccountLock=TRUE). The secUser
        // MODIFY runs first so a failure to mark the demographic leaves a
        // recoverable state (secUser disabled, demographic re-markable on a
        // retry). demographicDisableMark refuses up-front when there's nothing
        // configured to write, so the operator's explicit "mark" choice can
        // never silently degrade to a no-op.
        if (cfg.getOnDemographicDelete() == IsvaDemographicDeleteMode.DISABLE_AND_MARK) {
            steps.add(demographicDisableMark(dir, demographicDn));
        }

        return new DeletePlan(steps);
    }

    /**
     * Builds the demographic-side disable marker for DISABLE_AND_MARK, reusing
     * the directory's configured enable/disable attribute and disable value —
     * the same mapping {@code LdapUserService.disableUser} applies (BOOLEAN
     * writes a fixed {@code FALSE}; STRING writes the configured disable value).
     * Refuses when nothing is configured to write, so an explicit "mark" choice
     * can never degrade to a silent no-op.
     */
    private ModifyStep demographicDisableMark(DirectoryConnection dir, String demographicDn) {
        String attr = dir.getEnableDisableAttribute();
        if (attr == null || attr.isBlank()) {
            throw new ProvisioningRefusedException(
                    "on_demographic_delete=DISABLE_AND_MARK on directory "
                            + dir.getDisplayName() + " requires an enable/disable attribute to "
                            + "mark the demographic entry, but none is configured. Set the "
                            + "directory's enable/disable attribute (and disable value), or "
                            + "switch on_demographic_delete to LEAVE.");
        }
        String value;
        if (dir.getEnableDisableValueType() == EnableDisableValueType.BOOLEAN) {
            value = "FALSE";
        } else {
            value = dir.getDisableValue();
            if (value == null || value.isBlank()) {
                throw new ProvisioningRefusedException(
                        "on_demographic_delete=DISABLE_AND_MARK on directory "
                                + dir.getDisplayName() + " uses a STRING enable/disable attribute ("
                                + attr + ") but no disable value is configured. Set the directory's "
                                + "disable value, or switch on_demographic_delete to LEAVE.");
            }
        }
        return ModifyStep.of(demographicDn,
                List.of(new Modification(ModificationType.REPLACE, attr, value)));
    }

    // ── password set ─────────────────────────────────────────────────

    @Override
    public PasswordPlan planPasswordSet(DirectoryConnection dir,
                                         String demographicDn,
                                         PasswordSetPayload payload,
                                         ProvisioningContext ctx) {
        VendorIntegrationIsvaConfig cfg = activeConfigOrNull(dir);
        if (cfg == null || isExempt(ctx)) {
            return BaselinePlans.passwordSet(dir, demographicDn, payload);
        }

        return switch (cfg.getTopologyMode()) {
            case INLINE -> planInlinePasswordSet(dir, demographicDn, payload);
            case LINKED -> planLinkedPasswordSet(dir, cfg, demographicDn, payload);
        };
    }

    private PasswordPlan planInlinePasswordSet(DirectoryConnection dir,
                                                 String demographicDn,
                                                 PasswordSetPayload payload) {
        PasswordPlan basePlan = BaselinePlans.passwordSet(dir, demographicDn, payload);
        ModifyStep baseStep = (ModifyStep) basePlan.steps().get(0);
        List<Modification> mods = new ArrayList<>(baseStep.mods());
        mods.add(new Modification(ModificationType.REPLACE,
                "secPwdLastChanged", IsvaSecUserPlans.generalizedTime(Instant.now())));
        mods.add(new Modification(ModificationType.REPLACE,
                "secPwdValid", "TRUE"));
        return PasswordPlan.singleStep(ModifyStep.of(demographicDn, mods));
    }

    private PasswordPlan planLinkedPasswordSet(DirectoryConnection dir,
                                                  VendorIntegrationIsvaConfig cfg,
                                                  String demographicDn,
                                                  PasswordSetPayload payload) {
        Optional<String> secUserDn = linkedUserLookup.findSecUserDn(
                dir, cfg.getManagementDitBaseDn(), demographicDn);
        if (secUserDn.isEmpty()) {
            throw new ProvisioningRefusedException(
                    "No linked secUser entry found for demographic DN " + demographicDn
                            + ". Password change skipped — ISVA's rotation timer would "
                            + "report stale otherwise. Repair via pdadmin user import.");
        }

        // Step 1: write userPassword to the demographic DN (this
        // is what bind-auth uses). Reuse the baseline so AD's
        // UTF-16LE encoding is applied uniformly.
        PasswordPlan basePlan = BaselinePlans.passwordSet(dir, demographicDn, payload);
        ModifyStep demographicStep = (ModifyStep) basePlan.steps().get(0);

        // Step 2: stamp secPwdLastChanged + reset secPwdValid on
        // the secUser entry, so ISVA's password-state engine
        // agrees with reality.
        List<Modification> secUserMods = List.of(
                new Modification(ModificationType.REPLACE,
                        "secPwdLastChanged", IsvaSecUserPlans.generalizedTime(Instant.now())),
                new Modification(ModificationType.REPLACE, "secPwdValid", "TRUE"));
        ModifyStep secUserStep = ModifyStep.of(secUserDn.get(), secUserMods);

        return new PasswordPlan(List.of(demographicStep, secUserStep));
    }

    // ── group membership ─────────────────────────────────────────────

    @Override
    public GroupMemberPlan planGroupMembership(DirectoryConnection dir,
                                                String groupDn,
                                                String memberAttribute,
                                                String memberValue,
                                                ProvisioningContext ctx) {
        VendorIntegrationIsvaConfig cfg = activeConfigOrNull(dir);
        if (cfg == null || isExempt(ctx)) {
            return BaselinePlans.groupMembership(groupDn, memberAttribute, memberValue);
        }

        // require_sec_group gate (both topologies): a membership in a group
        // without the secGroup overlay succeeds at the LDAP level but is
        // invisible to ISVA — a loud refusal beats silently-wrong ACLs.
        // "Can't read the group" deliberately proceeds: the MODIFY then
        // fails with the server's own (clearer) error if the group really
        // is missing.
        if (cfg.isRequireSecGroup()) {
            Optional<Boolean> hasSecGroup =
                    groupShapeCheck.hasObjectClass(dir, groupDn, "secGroup");
            if (hasSecGroup.isPresent() && !hasSecGroup.get()) {
                return GroupMemberPlan.refuse(
                        "Group " + groupDn + " does not carry objectClass secGroup, and this "
                                + "directory's IVIA integration is configured to require it "
                                + "(memberships in non-secGroup groups are ignored by ISVA). "
                                + "Add the secGroup objectClass to the group (pdadmin group "
                                + "import), or turn off 'Require secGroup' in the IVIA config.");
            }
        }

        return switch (cfg.getTopologyMode()) {
            case INLINE -> BaselinePlans.groupMembership(groupDn, memberAttribute, memberValue);
            case LINKED -> planLinkedGroupMembership(dir, cfg, groupDn, memberAttribute, memberValue);
        };
    }

    private GroupMemberPlan planLinkedGroupMembership(DirectoryConnection dir,
                                                       VendorIntegrationIsvaConfig cfg,
                                                       String groupDn,
                                                       String memberAttribute,
                                                       String memberValue) {
        // memberValue is the demographic DN (services pass that
        // through; the interceptor decides what to actually write).
        if (cfg.getGroupMemberTarget() == IsvaGroupMemberTarget.DEMOGRAPHIC_DN) {
            return BaselinePlans.groupMembership(groupDn, memberAttribute, memberValue);
        }

        // SECUSER_DN: resolve via lookup, write secUser DN as the
        // member value.
        Optional<String> secUserDn = linkedUserLookup.findSecUserDn(
                dir, cfg.getManagementDitBaseDn(), memberValue);
        if (secUserDn.isEmpty()) {
            // SECUSER_DN with no secUser → refuse. Silent fall-back
            // to demographic DN would silently disagree with this
            // deployment's ACL conventions.
            return GroupMemberPlan.refuse(
                    "Group target is configured as SECUSER_DN, but no linked secUser "
                            + "entry was found for demographic DN " + memberValue
                            + " under " + cfg.getManagementDitBaseDn()
                            + ". Refusing the membership add; repair the orphan first.");
        }
        return BaselinePlans.groupMembership(groupDn, memberAttribute, secUserDn.get());
    }

    // ── helpers ──────────────────────────────────────────────────────

    /**
     * Whether ISVA should stand down for this operation and let the
     * baseline plan through. Two independent reasons:
     * <ul>
     *   <li>the resolved profile is ISVA-exempt ({@code FORCE_OFF}); or</li>
     *   <li>the caller explicitly suppressed the vendor overlay for this
     *       operation ({@link ProvisioningContext#suppressVendorOverlay()})
     *       — e.g. an LDIF import where the operator declined secUser
     *       provisioning.</li>
     * </ul>
     * The per-directory kill switch is handled separately by
     * {@link #activeConfigOrNull}. A null context / profile id is never
     * exempt on its own.
     */
    private boolean isExempt(ProvisioningContext ctx) {
        if (ctx != null && ctx.suppressVendorOverlay()) {
            return true;
        }
        return overrideService.isExemptFromIvia(ctx == null ? null : ctx.profileId());
    }

    /**
     * Returns the config row when the addon is enabled for this
     * directory, or null when there's no row / enabled=false. Null
     * means "fall through to baseline" everywhere.
     */
    private VendorIntegrationIsvaConfig activeConfigOrNull(DirectoryConnection dir) {
        if (dir == null || dir.getId() == null) {
            return null;
        }
        Optional<VendorIntegrationIsvaConfig> maybe = configRepo.findById(dir.getId());
        if (maybe.isEmpty() || !maybe.get().isEnabled()) {
            return null;
        }
        return maybe.get();
    }
}
