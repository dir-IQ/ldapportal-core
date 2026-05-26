// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva;

import com.ldapportal.addons.isva.entity.IsvaDeletePolicy;
import com.ldapportal.addons.isva.entity.IsvaTopologyMode;
import com.ldapportal.addons.isva.entity.VendorIntegrationIsvaConfig;
import com.ldapportal.addons.isva.repository.VendorIntegrationIsvaConfigRepository;
import com.ldapportal.addons.isva.service.IsvaProfileOverrideService;
import com.ldapportal.core.provisioning.AddStep;
import com.ldapportal.core.provisioning.DeletePlan;
import com.ldapportal.core.provisioning.DeleteStep;
import com.ldapportal.core.provisioning.GroupMemberPlan;
import com.ldapportal.core.provisioning.ModifyStep;
import com.ldapportal.core.provisioning.PasswordPlan;
import com.ldapportal.core.provisioning.PasswordSetPayload;
import com.ldapportal.core.provisioning.ProvisioningContext;
import com.ldapportal.core.provisioning.StepFailurePolicy;
import com.ldapportal.core.provisioning.UserCreatePayload;
import com.ldapportal.core.provisioning.UserCreatePlan;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.DirectoryType;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Inline-mode behaviour of {@link IsvaProvisioningInterceptor}.
 * Repository is mocked so each test can swap in the config row
 * shape it wants to exercise. LINKED-mode branches are covered
 * here only via the "throws UnsupportedOperationException"
 * placeholder assertions — full LINKED coverage lands in P3.
 */
@ExtendWith(MockitoExtension.class)
class IsvaProvisioningInterceptorTest {

    @Mock private VendorIntegrationIsvaConfigRepository configRepo;
    @Mock private IsvaLinkedUserLookup linkedUserLookup;
    @Mock private IsvaProfileOverrideService overrideService;

    private IsvaProvisioningInterceptor interceptor;
    private DirectoryConnection dir;

    @BeforeEach
    void setUp() {
        // Real IsvaSecUserPlans — it's a stateless plan-fragment builder, and
        // these regression tests assert the resulting plan shapes, so the
        // genuine implementation is what we want to exercise.
        interceptor = new IsvaProvisioningInterceptor(
                configRepo, linkedUserLookup, overrideService, new IsvaSecUserPlans());
        dir = new DirectoryConnection();
        dir.setId(UUID.randomUUID());
        dir.setDirectoryType(DirectoryType.OPENLDAP);
    }

    // ── disabled / absent config falls through to baseline ──────────

    @Test
    void create_noConfigRow_fallsBackToBaselinePlan() {
        when(configRepo.findById(dir.getId())).thenReturn(Optional.empty());

        UserCreatePlan plan = interceptor.planUserCreate(dir,
                UserCreatePayload.of("uid=alice,dc=x", Map.of(
                        "objectClass", List.of("inetOrgPerson"),
                        "uid", List.of("alice"),
                        "cn", List.of("Alice"), "sn", List.of("A"))), ProvisioningContext.empty());

        // Baseline single-step ADD; objectClass list does NOT include secUser.
        assertThat(plan.steps()).hasSize(1);
        AddStep step = (AddStep) plan.steps().get(0);
        assertThat(objectClassValues(step.attributes()))
                .containsExactly("inetOrgPerson")
                .doesNotContain("secUser");
    }

    @Test
    void create_configDisabled_fallsBackToBaselinePlan() {
        VendorIntegrationIsvaConfig cfg = inlineConfig();
        cfg.setEnabled(false);
        when(configRepo.findById(dir.getId())).thenReturn(Optional.of(cfg));

        UserCreatePlan plan = interceptor.planUserCreate(dir,
                UserCreatePayload.of("uid=alice,dc=x", Map.of(
                        "objectClass", List.of("inetOrgPerson"),
                        "uid", List.of("alice"))), ProvisioningContext.empty());

        AddStep step = (AddStep) plan.steps().get(0);
        assertThat(objectClassValues(step.attributes())).doesNotContain("secUser");
    }

    // ── inline-mode create ─────────────────────────────────────────

    @Test
    void create_inline_addsSecUserObjectClass_andSecStarAttributes() {
        when(configRepo.findById(dir.getId())).thenReturn(Optional.of(inlineConfig()));

        UserCreatePlan plan = interceptor.planUserCreate(dir,
                UserCreatePayload.of("uid=alice,dc=x", Map.of(
                        "objectClass", List.of("inetOrgPerson", "person"),
                        "uid", List.of("alice"),
                        "cn", List.of("Alice"), "sn", List.of("A"))), ProvisioningContext.empty());

        assertThat(plan.steps()).hasSize(1);
        AddStep step = (AddStep) plan.steps().get(0);
        assertThat(step.targetDn()).isEqualTo("uid=alice,dc=x");

        // objectClass list preserves pre-existing values + adds secUser.
        assertThat(objectClassValues(step.attributes()))
                .containsExactlyInAnyOrder("inetOrgPerson", "person", "secUser");

        // sec* attributes present with the expected defaults.
        assertThat(attrValue(step.attributes(), "secLogin")).isEqualTo("alice");
        assertThat(attrValue(step.attributes(), "secAuthority")).isEqualTo("Default");
        assertThat(attrValue(step.attributes(), "secAcctValid")).isEqualTo("TRUE");
        assertThat(attrValue(step.attributes(), "secPwdValid")).isEqualTo("TRUE");
        // Generalized time format: yyyyMMddHHmmss'Z'
        assertThat(attrValue(step.attributes(), "secValidUntil"))
                .matches("\\d{14}Z");
        assertThat(attrValue(step.attributes(), "secPwdLastChanged"))
                .matches("\\d{14}Z");
    }

    @Test
    void create_inline_secAuthorityOverridable() {
        VendorIntegrationIsvaConfig cfg = inlineConfig();
        cfg.setSecAuthority("EUR-Region");
        when(configRepo.findById(dir.getId())).thenReturn(Optional.of(cfg));

        UserCreatePlan plan = interceptor.planUserCreate(dir,
                UserCreatePayload.of("uid=alice,dc=x", Map.of(
                        "objectClass", List.of("inetOrgPerson"),
                        "uid", List.of("alice"))), ProvisioningContext.empty());

        AddStep step = (AddStep) plan.steps().get(0);
        assertThat(attrValue(step.attributes(), "secAuthority")).isEqualTo("EUR-Region");
    }

    @Test
    void create_inline_missingUid_yieldsEmptySecLogin() {
        // Defensive: secLogin defaults to "" rather than throwing
        // when the operator's payload doesn't carry uid. The downstream
        // LDAP add will fail with a schema violation anyway, which is
        // a better signal than a NullPointerException here.
        when(configRepo.findById(dir.getId())).thenReturn(Optional.of(inlineConfig()));

        UserCreatePlan plan = interceptor.planUserCreate(dir,
                UserCreatePayload.of("uid=alice,dc=x", Map.of(
                        "objectClass", List.of("inetOrgPerson"))), ProvisioningContext.empty());

        AddStep step = (AddStep) plan.steps().get(0);
        assertThat(attrValue(step.attributes(), "secLogin")).isEmpty();
    }

    // ── inline-mode delete ────────────────────────────────────────

    @Test
    void delete_inline_default_disableSoftRather_thanHard() {
        VendorIntegrationIsvaConfig cfg = inlineConfig();
        // delete_policy = DISABLE is the default; assert it explicitly.
        assertThat(cfg.getDeletePolicy()).isEqualTo(IsvaDeletePolicy.DISABLE);
        when(configRepo.findById(dir.getId())).thenReturn(Optional.of(cfg));

        DeletePlan plan = interceptor.planUserDelete(dir, "uid=alice,dc=x", ProvisioningContext.empty());

        assertThat(plan.steps()).hasSize(1);
        ModifyStep step = (ModifyStep) plan.steps().get(0);
        assertThat(step.targetDn()).isEqualTo("uid=alice,dc=x");
        assertThat(modValue(step.mods(), "secAcctValid")).isEqualTo("FALSE");
        assertThat(modValue(step.mods(), "secValidUntil")).matches("\\d{14}Z");
    }

    @Test
    void delete_inline_hardDelete_returnsDelStep() {
        VendorIntegrationIsvaConfig cfg = inlineConfig();
        cfg.setDeletePolicy(IsvaDeletePolicy.HARD_DELETE);
        when(configRepo.findById(dir.getId())).thenReturn(Optional.of(cfg));

        DeletePlan plan = interceptor.planUserDelete(dir, "uid=alice,dc=x", ProvisioningContext.empty());

        assertThat(plan.steps()).hasSize(1);
        assertThat(plan.steps().get(0)).isInstanceOf(DeleteStep.class);
        assertThat(plan.steps().get(0).targetDn()).isEqualTo("uid=alice,dc=x");
    }

    @Test
    void delete_noConfig_fallsBackToBaselineHardDelete() {
        when(configRepo.findById(dir.getId())).thenReturn(Optional.empty());

        DeletePlan plan = interceptor.planUserDelete(dir, "uid=alice,dc=x", ProvisioningContext.empty());

        // Baseline = single DEL.
        assertThat(plan.steps().get(0)).isInstanceOf(DeleteStep.class);
    }

    // ── inline-mode password set ────────────────────────────────────

    @Test
    void password_inline_combinesUserPasswordAndSecPwdLastChanged_inOneModify() {
        when(configRepo.findById(dir.getId())).thenReturn(Optional.of(inlineConfig()));

        PasswordPlan plan = interceptor.planPasswordSet(dir, "uid=alice,dc=x",
                new PasswordSetPayload("hunter2"), ProvisioningContext.empty());

        assertThat(plan.steps()).hasSize(1);
        ModifyStep step = (ModifyStep) plan.steps().get(0);
        // One MODIFY against one DN, carrying 3 modifications: userPassword,
        // secPwdLastChanged, secPwdValid=TRUE.
        assertThat(step.mods()).hasSize(3);
        assertThat(modValue(step.mods(), "userPassword")).isEqualTo("hunter2");
        assertThat(modValue(step.mods(), "secPwdLastChanged")).matches("\\d{14}Z");
        assertThat(modValue(step.mods(), "secPwdValid")).isEqualTo("TRUE");
    }

    @Test
    void password_inline_adDirectoryUsesUnicodePwd() {
        // The interceptor delegates to BaselinePlans.passwordSet for the
        // password mod itself, so AD's UTF-16LE quirk should still apply.
        dir.setDirectoryType(DirectoryType.ACTIVE_DIRECTORY);
        when(configRepo.findById(dir.getId())).thenReturn(Optional.of(inlineConfig()));

        PasswordPlan plan = interceptor.planPasswordSet(dir, "CN=Alice,DC=x",
                new PasswordSetPayload("hunter2"), ProvisioningContext.empty());

        ModifyStep step = (ModifyStep) plan.steps().get(0);
        // Find the unicodePwd modification specifically.
        Modification pwdMod = step.mods().stream()
                .filter(m -> "unicodePwd".equalsIgnoreCase(m.getAttributeName()))
                .findFirst().orElseThrow();
        byte[] expected = "\"hunter2\"".getBytes(java.nio.charset.Charset.forName("UTF-16LE"));
        assertThat(pwdMod.getValueByteArrays()[0]).isEqualTo(expected);
    }

    // ── inline-mode group membership ───────────────────────────────

    @Test
    void groupMembership_inline_proceedsAsBaseline() {
        when(configRepo.findById(dir.getId())).thenReturn(Optional.of(inlineConfig()));

        GroupMemberPlan plan = interceptor.planGroupMembership(dir,
                "cn=eng,dc=x", "member", "uid=alice,dc=x", ProvisioningContext.empty());

        // v1: interceptor produces the baseline proceeding plan. The
        // secGroup validation is a future addition (see P4); test
        // pinned to current behaviour.
        assertThat(plan.isRefused()).isFalse();
        assertThat(plan.steps()).hasSize(1);
        ModifyStep step = (ModifyStep) plan.steps().get(0);
        Modification mod = step.mods().get(0);
        assertThat(mod.getModificationType()).isEqualTo(ModificationType.ADD);
        assertThat(mod.getAttributeName()).isEqualTo("member");
        assertThat(mod.getValues()[0]).isEqualTo("uid=alice,dc=x");
    }

    // ── LINKED-mode: create ────────────────────────────────────────

    @Test
    void create_linked_producesTwoStepPlan_withCompensation() {
        // Demographic ADD first, secUser ADD second under the
        // configured management DIT. The secUser step uses COMPENSATE
        // so a failure cleans up the demographic.
        when(configRepo.findById(dir.getId())).thenReturn(Optional.of(linkedConfig()));

        UserCreatePlan plan = interceptor.planUserCreate(dir,
                UserCreatePayload.of("uid=alice,ou=people,dc=x", Map.of(
                        "objectClass", List.of("inetOrgPerson"),
                        "uid", List.of("alice"),
                        "cn", List.of("Alice"), "sn", List.of("A"))), ProvisioningContext.empty());

        assertThat(plan.steps()).hasSize(2);

        // Step 1: demographic ADD, default ABORT policy, no sec*
        // attributes (linked mode keeps demographic clean).
        AddStep step1 = (AddStep) plan.steps().get(0);
        assertThat(step1.targetDn()).isEqualTo("uid=alice,ou=people,dc=x");
        assertThat(step1.onFailure()).isEqualTo(StepFailurePolicy.ABORT);
        assertThat(attrValue(step1.attributes(), "secLogin"))
                .as("demographic entry must not carry sec* attributes")
                .isNull();
        assertThat(objectClassValues(step1.attributes()))
                .containsExactly("inetOrgPerson")
                .doesNotContain("secUser");

        // Step 2: secUser ADD under management DIT, COMPENSATE policy.
        AddStep step2 = (AddStep) plan.steps().get(1);
        assertThat(step2.onFailure()).isEqualTo(StepFailurePolicy.COMPENSATE);
        assertThat(step2.targetDn())
                .endsWith(",secAuthority=Default,o=acme,c=us")
                .startsWith("secUUID=");
        // secUser carries the sec* defaults + the back-reference.
        assertThat(objectClassValues(step2.attributes())).contains("secUser");
        assertThat(attrValue(step2.attributes(), "secDN"))
                .isEqualTo("uid=alice,ou=people,dc=x");
        assertThat(attrValue(step2.attributes(), "secLogin")).isEqualTo("alice");
        assertThat(attrValue(step2.attributes(), "secAcctValid")).isEqualTo("TRUE");

        // Compensation block deletes the demographic entry on a
        // failed secUser step.
        assertThat(plan.compensation()).isPresent();
        List<com.ldapportal.core.provisioning.LdapOperationStep> comp =
                plan.compensation().orElseThrow();
        assertThat(comp).hasSize(1);
        assertThat(comp.get(0))
                .isInstanceOf(com.ldapportal.core.provisioning.DeleteStep.class);
        assertThat(comp.get(0).targetDn()).isEqualTo("uid=alice,ou=people,dc=x");
    }

    @Test
    void create_linked_secLoginRdn_usesUidAsRdnValue() {
        // Some deployments use secLogin as the RDN rather than the
        // generated secUUID — pin that path too.
        VendorIntegrationIsvaConfig cfg = linkedConfig();
        cfg.setSecuserRdnAttribute("secLogin");
        when(configRepo.findById(dir.getId())).thenReturn(Optional.of(cfg));

        UserCreatePlan plan = interceptor.planUserCreate(dir,
                UserCreatePayload.of("uid=alice,dc=x", Map.of(
                        "uid", List.of("alice"),
                        "objectClass", List.of("inetOrgPerson"))), ProvisioningContext.empty());

        AddStep secUserStep = (AddStep) plan.steps().get(1);
        assertThat(secUserStep.targetDn())
                .startsWith("secLogin=alice,")
                .endsWith(",secAuthority=Default,o=acme,c=us");
        // RDN attribute mirrored on the entry too.
        assertThat(attrValue(secUserStep.attributes(), "secLogin")).isEqualTo("alice");
    }

    // ── LINKED-mode: delete ────────────────────────────────────────

    @Test
    void delete_linked_disable_modifiesSecUserOnly() {
        // DISABLE policy (the default) — single MODIFY against the
        // secUser DN; demographic untouched.
        when(configRepo.findById(dir.getId())).thenReturn(Optional.of(linkedConfig()));
        when(linkedUserLookup.findSecUserDn(
                eq(dir), eq("secAuthority=Default,o=acme,c=us"), eq("uid=alice,dc=x")))
                .thenReturn(Optional.of("secUUID=abc,secAuthority=Default,o=acme,c=us"));

        DeletePlan plan = interceptor.planUserDelete(dir, "uid=alice,dc=x", ProvisioningContext.empty());

        assertThat(plan.steps()).hasSize(1);
        ModifyStep step = (ModifyStep) plan.steps().get(0);
        assertThat(step.targetDn()).isEqualTo("secUUID=abc,secAuthority=Default,o=acme,c=us");
        assertThat(modValue(step.mods(), "secAcctValid")).isEqualTo("FALSE");
    }

    @Test
    void delete_linked_disable_orphanedDemographic_refuses() {
        // No paired secUser → refuse. Operator can hard-delete to
        // remove just the demographic; can't disable what isn't
        // there.
        when(configRepo.findById(dir.getId())).thenReturn(Optional.of(linkedConfig()));
        when(linkedUserLookup.findSecUserDn(
                eq(dir), eq("secAuthority=Default,o=acme,c=us"), eq("uid=orphan,dc=x")))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> interceptor.planUserDelete(dir, "uid=orphan,dc=x", ProvisioningContext.empty()))
                .isInstanceOf(com.ldapportal.core.provisioning.ProvisioningRefusedException.class)
                .hasMessageContaining("orphaned demographic")
                .hasMessageContaining("hard-delete");
    }

    @Test
    void delete_linked_hardDelete_twoStepSecUserFirst() {
        // HARD_DELETE: DEL secUser then DEL demographic, in that
        // order, so a step-2 failure leaves a recoverable state.
        VendorIntegrationIsvaConfig cfg = linkedConfig();
        cfg.setDeletePolicy(IsvaDeletePolicy.HARD_DELETE);
        when(configRepo.findById(dir.getId())).thenReturn(Optional.of(cfg));
        when(linkedUserLookup.findSecUserDn(
                eq(dir), eq("secAuthority=Default,o=acme,c=us"), eq("uid=alice,dc=x")))
                .thenReturn(Optional.of("secUUID=abc,secAuthority=Default,o=acme,c=us"));

        DeletePlan plan = interceptor.planUserDelete(dir, "uid=alice,dc=x", ProvisioningContext.empty());

        assertThat(plan.steps()).hasSize(2);
        assertThat(plan.steps().get(0).targetDn())
                .isEqualTo("secUUID=abc,secAuthority=Default,o=acme,c=us");
        assertThat(plan.steps().get(1).targetDn()).isEqualTo("uid=alice,dc=x");
        assertThat(plan.steps()).allMatch(s ->
                s instanceof com.ldapportal.core.provisioning.DeleteStep);
    }

    @Test
    void delete_linked_hardDelete_orphanedDemographic_deletesDemographicOnly() {
        // Orphaned demographic + HARD_DELETE: still delete the
        // demographic. This is the operator's recovery path.
        VendorIntegrationIsvaConfig cfg = linkedConfig();
        cfg.setDeletePolicy(IsvaDeletePolicy.HARD_DELETE);
        when(configRepo.findById(dir.getId())).thenReturn(Optional.of(cfg));
        when(linkedUserLookup.findSecUserDn(
                eq(dir), eq("secAuthority=Default,o=acme,c=us"), eq("uid=orphan,dc=x")))
                .thenReturn(Optional.empty());

        DeletePlan plan = interceptor.planUserDelete(dir, "uid=orphan,dc=x", ProvisioningContext.empty());

        assertThat(plan.steps()).hasSize(1);
        assertThat(plan.steps().get(0).targetDn()).isEqualTo("uid=orphan,dc=x");
    }

    // ── LINKED-mode: password set ──────────────────────────────────

    @Test
    void password_linked_twoStepModify_demographicAndSecUser() {
        // Step 1: userPassword REPLACE on demographic DN (uses the
        // baseline so AD's UTF-16LE quirk still applies). Step 2:
        // secPwdLastChanged + secPwdValid on the secUser DN.
        when(configRepo.findById(dir.getId())).thenReturn(Optional.of(linkedConfig()));
        when(linkedUserLookup.findSecUserDn(
                eq(dir), eq("secAuthority=Default,o=acme,c=us"), eq("uid=alice,dc=x")))
                .thenReturn(Optional.of("secUUID=abc,secAuthority=Default,o=acme,c=us"));

        PasswordPlan plan = interceptor.planPasswordSet(dir, "uid=alice,dc=x",
                new PasswordSetPayload("hunter2"), ProvisioningContext.empty());

        assertThat(plan.steps()).hasSize(2);
        ModifyStep demographicStep = (ModifyStep) plan.steps().get(0);
        assertThat(demographicStep.targetDn()).isEqualTo("uid=alice,dc=x");
        assertThat(modValue(demographicStep.mods(), "userPassword")).isEqualTo("hunter2");

        ModifyStep secUserStep = (ModifyStep) plan.steps().get(1);
        assertThat(secUserStep.targetDn()).isEqualTo("secUUID=abc,secAuthority=Default,o=acme,c=us");
        assertThat(modValue(secUserStep.mods(), "secPwdLastChanged")).matches("\\d{14}Z");
        assertThat(modValue(secUserStep.mods(), "secPwdValid")).isEqualTo("TRUE");
    }

    @Test
    void password_linked_orphanedDemographic_refuses() {
        // Without a secUser, the password write alone would leave
        // ISVA's rotation timer reporting stale. Refuse.
        when(configRepo.findById(dir.getId())).thenReturn(Optional.of(linkedConfig()));
        when(linkedUserLookup.findSecUserDn(
                eq(dir), eq("secAuthority=Default,o=acme,c=us"), eq("uid=orphan,dc=x")))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> interceptor.planPasswordSet(dir, "uid=orphan,dc=x",
                new PasswordSetPayload("pw"), ProvisioningContext.empty()))
                .isInstanceOf(com.ldapportal.core.provisioning.ProvisioningRefusedException.class);
    }

    // ── LINKED-mode: group membership ──────────────────────────────

    @Test
    void groupMembership_linked_demographicTarget_writesDemographicDn() {
        // DEMOGRAPHIC_DN (the default) — same as baseline, no
        // lookup needed.
        when(configRepo.findById(dir.getId())).thenReturn(Optional.of(linkedConfig()));

        GroupMemberPlan plan = interceptor.planGroupMembership(dir,
                "cn=eng,dc=x", "member", "uid=alice,dc=x", ProvisioningContext.empty());

        assertThat(plan.isRefused()).isFalse();
        ModifyStep step = (ModifyStep) plan.steps().get(0);
        Modification mod = step.mods().get(0);
        assertThat(mod.getValues()[0]).isEqualTo("uid=alice,dc=x");
        // No lookup performed for DEMOGRAPHIC_DN.
        org.mockito.Mockito.verifyNoInteractions(linkedUserLookup);
    }

    @Test
    void groupMembership_linked_secUserTarget_writesSecUserDn() {
        // SECUSER_DN — lookup the paired secUser, write its DN.
        VendorIntegrationIsvaConfig cfg = linkedConfig();
        cfg.setGroupMemberTarget(
                com.ldapportal.addons.isva.entity.IsvaGroupMemberTarget.SECUSER_DN);
        when(configRepo.findById(dir.getId())).thenReturn(Optional.of(cfg));
        when(linkedUserLookup.findSecUserDn(
                eq(dir), eq("secAuthority=Default,o=acme,c=us"), eq("uid=alice,dc=x")))
                .thenReturn(Optional.of("secUUID=abc,secAuthority=Default,o=acme,c=us"));

        GroupMemberPlan plan = interceptor.planGroupMembership(dir,
                "cn=eng,dc=x", "member", "uid=alice,dc=x", ProvisioningContext.empty());

        assertThat(plan.isRefused()).isFalse();
        ModifyStep step = (ModifyStep) plan.steps().get(0);
        assertThat(step.mods().get(0).getValues()[0])
                .isEqualTo("secUUID=abc,secAuthority=Default,o=acme,c=us");
    }

    @Test
    void groupMembership_linked_secUserTarget_orphanedDemographic_refuses() {
        // SECUSER_DN but no secUser exists — refuse. Falling back
        // to demographic would silently disagree with the
        // deployment's ACL conventions.
        VendorIntegrationIsvaConfig cfg = linkedConfig();
        cfg.setGroupMemberTarget(
                com.ldapportal.addons.isva.entity.IsvaGroupMemberTarget.SECUSER_DN);
        when(configRepo.findById(dir.getId())).thenReturn(Optional.of(cfg));
        when(linkedUserLookup.findSecUserDn(
                eq(dir), eq("secAuthority=Default,o=acme,c=us"), eq("uid=orphan,dc=x")))
                .thenReturn(Optional.empty());

        GroupMemberPlan plan = interceptor.planGroupMembership(dir,
                "cn=eng,dc=x", "member", "uid=orphan,dc=x", ProvisioningContext.empty());

        assertThat(plan.isRefused()).isTrue();
        assertThat(plan.refusalReason().orElseThrow())
                .contains("SECUSER_DN")
                .contains("orphan");
    }

    // ── per-profile FORCE_OFF exemption ────────────────────────────

    private static final UUID EXEMPT_PROFILE = UUID.randomUUID();

    @Test
    void create_forceOffProfile_inEnabledDirectory_fallsBackToBaseline() {
        when(configRepo.findById(dir.getId())).thenReturn(Optional.of(inlineConfig()));
        when(overrideService.isExemptFromIvia(EXEMPT_PROFILE)).thenReturn(true);

        UserCreatePlan plan = interceptor.planUserCreate(dir,
                UserCreatePayload.of("uid=svc,dc=x", Map.of(
                        "objectClass", List.of("inetOrgPerson"),
                        "uid", List.of("svc"))),
                ProvisioningContext.of(EXEMPT_PROFILE));

        // Plain LDAP ADD — no secUser objectClass, no sec* attributes.
        assertThat(plan.steps()).hasSize(1);
        AddStep step = (AddStep) plan.steps().get(0);
        assertThat(objectClassValues(step.attributes()))
                .containsExactly("inetOrgPerson").doesNotContain("secUser");
        assertThat(attrValue(step.attributes(), "secLogin")).isNull();
    }

    @Test
    void delete_forceOffProfile_linkedDirectory_plainDelete_noLookupNoRefuse() {
        // Headline case: a FORCE_OFF entry in a LINKED directory must
        // NOT hit the orphan refuse path. Full exemption = a plain
        // LDAP DEL; the secUser lookup is never consulted.
        when(configRepo.findById(dir.getId())).thenReturn(Optional.of(linkedConfig()));
        when(overrideService.isExemptFromIvia(EXEMPT_PROFILE)).thenReturn(true);

        DeletePlan plan = interceptor.planUserDelete(dir, "uid=svc,dc=x",
                ProvisioningContext.of(EXEMPT_PROFILE));

        assertThat(plan.steps()).hasSize(1);
        assertThat(plan.steps().get(0)).isInstanceOf(DeleteStep.class);
        assertThat(plan.steps().get(0).targetDn()).isEqualTo("uid=svc,dc=x");
        org.mockito.Mockito.verifyNoInteractions(linkedUserLookup);
    }

    @Test
    void password_forceOffProfile_linkedDirectory_plainPassword_noRefuse() {
        when(configRepo.findById(dir.getId())).thenReturn(Optional.of(linkedConfig()));
        when(overrideService.isExemptFromIvia(EXEMPT_PROFILE)).thenReturn(true);

        PasswordPlan plan = interceptor.planPasswordSet(dir, "uid=svc,dc=x",
                new PasswordSetPayload("pw"), ProvisioningContext.of(EXEMPT_PROFILE));

        assertThat(plan.steps()).hasSize(1);
        ModifyStep step = (ModifyStep) plan.steps().get(0);
        assertThat(step.mods()).hasSize(1);
        assertThat(modValue(step.mods(), "userPassword")).isEqualTo("pw");
        org.mockito.Mockito.verifyNoInteractions(linkedUserLookup);
    }

    @Test
    void groupMembership_forceOffProfile_linkedSecUserTarget_baselineMembership_noRefuse() {
        VendorIntegrationIsvaConfig cfg = linkedConfig();
        cfg.setGroupMemberTarget(
                com.ldapportal.addons.isva.entity.IsvaGroupMemberTarget.SECUSER_DN);
        when(configRepo.findById(dir.getId())).thenReturn(Optional.of(cfg));
        when(overrideService.isExemptFromIvia(EXEMPT_PROFILE)).thenReturn(true);

        GroupMemberPlan plan = interceptor.planGroupMembership(dir,
                "cn=eng,dc=x", "member", "uid=svc,dc=x",
                ProvisioningContext.of(EXEMPT_PROFILE));

        // Demographic DN written, no SECUSER_DN rewrite, no refusal.
        assertThat(plan.isRefused()).isFalse();
        ModifyStep step = (ModifyStep) plan.steps().get(0);
        assertThat(step.mods().get(0).getValues()[0]).isEqualTo("uid=svc,dc=x");
        org.mockito.Mockito.verifyNoInteractions(linkedUserLookup);
    }

    @Test
    void create_inheritProfile_inEnabledDirectory_appliesIvia() {
        // A resolved profile that is NOT exempt (INHERIT) → IVIA still
        // applies, exactly as the no-profile path does.
        when(configRepo.findById(dir.getId())).thenReturn(Optional.of(inlineConfig()));
        when(overrideService.isExemptFromIvia(EXEMPT_PROFILE)).thenReturn(false);

        UserCreatePlan plan = interceptor.planUserCreate(dir,
                UserCreatePayload.of("uid=alice,dc=x", Map.of(
                        "objectClass", List.of("inetOrgPerson"),
                        "uid", List.of("alice"))),
                ProvisioningContext.of(EXEMPT_PROFILE));

        AddStep step = (AddStep) plan.steps().get(0);
        assertThat(objectClassValues(step.attributes())).contains("secUser");
    }

    // ── helpers ────────────────────────────────────────────────────

    private VendorIntegrationIsvaConfig inlineConfig() {
        VendorIntegrationIsvaConfig cfg = new VendorIntegrationIsvaConfig();
        cfg.setDirectoryConnectionId(dir.getId());
        cfg.setEnabled(true);
        cfg.setTopologyMode(IsvaTopologyMode.INLINE);
        return cfg;
    }

    private VendorIntegrationIsvaConfig linkedConfig() {
        VendorIntegrationIsvaConfig cfg = inlineConfig();
        cfg.setTopologyMode(IsvaTopologyMode.LINKED);
        cfg.setManagementDitBaseDn("secAuthority=Default,o=acme,c=us");
        return cfg;
    }

    /** Pull every value of the {@code objectClass} attribute, in any case. */
    private static List<String> objectClassValues(List<Attribute> attrs) {
        return attrs.stream()
                .filter(a -> "objectClass".equalsIgnoreCase(a.getName()))
                .flatMap(a -> java.util.Arrays.stream(a.getValues()))
                .toList();
    }

    /** First value of the named attribute, or null if absent. */
    private static String attrValue(List<Attribute> attrs, String name) {
        return attrs.stream()
                .filter(a -> a.getName().equalsIgnoreCase(name))
                .map(a -> a.getValues()[0])
                .findFirst().orElse(null);
    }

    private static String modValue(List<Modification> mods, String attr) {
        return mods.stream()
                .filter(m -> m.getAttributeName().equalsIgnoreCase(attr))
                .map(m -> m.getValues()[0])
                .findFirst().orElse(null);
    }
}
