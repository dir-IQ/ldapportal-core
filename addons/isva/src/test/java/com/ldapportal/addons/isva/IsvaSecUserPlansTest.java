// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva;

import com.ldapportal.addons.isva.entity.IsvaTopologyMode;
import com.ldapportal.addons.isva.entity.VendorIntegrationIsvaConfig;
import com.ldapportal.core.provisioning.AddStep;
import com.ldapportal.core.provisioning.BaselinePlans;
import com.ldapportal.core.provisioning.DeleteStep;
import com.ldapportal.core.provisioning.ModifyStep;
import com.ldapportal.core.provisioning.StepFailurePolicy;
import com.ldapportal.core.provisioning.UserCreatePayload;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fragment-shape tests for {@link IsvaSecUserPlans} — the extracted,
 * stateless secUser plan builders. The lifecycle composition (how the
 * interceptor wires grant/disable/hardDelete into create/delete plans)
 * is covered by {@link IsvaProvisioningInterceptorTest}; here we pin the
 * individual fragments, including the account verbs that have no
 * lifecycle-path caller yet.
 */
class IsvaSecUserPlansTest {

    private final IsvaSecUserPlans plans = new IsvaSecUserPlans();

    // ── grant (inline, new entry) ───────────────────────────────────

    @Test
    void grantInline_addsSecUserObjectClass_andSecStarDefaults() {
        List<Attribute> attrs = plans.grantInline(
                BaselinePlans.attributesFromMap(Map.of(
                        "objectClass", List.of("inetOrgPerson", "person"),
                        "uid", List.of("alice"))),
                inlineConfig(), payload("uid=alice,dc=x", "alice"));

        assertThat(objectClassValues(attrs))
                .containsExactlyInAnyOrder("inetOrgPerson", "person", "secUser");
        assertThat(attrValue(attrs, "secLogin")).isEqualTo("alice");
        assertThat(attrValue(attrs, "secAuthority")).isEqualTo("Default");
        assertThat(attrValue(attrs, "secAcctValid")).isEqualTo("TRUE");
        assertThat(attrValue(attrs, "secPwdValid")).isEqualTo("TRUE");
        assertThat(attrValue(attrs, "secValidUntil")).matches("\\d{14}Z");
        assertThat(attrValue(attrs, "secPwdLastChanged")).matches("\\d{14}Z");
    }

    @Test
    void grantInline_defersToCallerSuppliedSecAttribute() {
        // A profile that already populated secAuthority must not be
        // overwritten by the ISVA default.
        List<Attribute> attrs = plans.grantInline(
                BaselinePlans.attributesFromMap(Map.of(
                        "objectClass", List.of("inetOrgPerson"),
                        "uid", List.of("alice"),
                        "secAuthority", List.of("EUR-Region"))),
                inlineConfig(), payload("uid=alice,dc=x", "alice"));

        assertThat(attrValue(attrs, "secAuthority")).isEqualTo("EUR-Region");
    }

    // ── grant (inline, existing entry) — the one genuinely new fragment ──

    @Test
    void grantInlineOnExisting_producesAddMods_forObjectClassAndSecStar() {
        ModifyStep step = plans.grantInlineOnExisting(
                "uid=alice,dc=x", inlineConfig(), "alice");

        assertThat(step.targetDn()).isEqualTo("uid=alice,dc=x");
        // Every modification is an ADD (we're layering onto an existing entry).
        assertThat(step.mods()).allMatch(m -> m.getModificationType() == ModificationType.ADD);
        assertThat(modValue(step.mods(), "objectClass")).isEqualTo("secUser");
        assertThat(modValue(step.mods(), "secLogin")).isEqualTo("alice");
        assertThat(modValue(step.mods(), "secAuthority")).isEqualTo("Default");
        assertThat(modValue(step.mods(), "secAcctValid")).isEqualTo("TRUE");
        assertThat(modValue(step.mods(), "secPwdValid")).isEqualTo("TRUE");
        assertThat(modValue(step.mods(), "secValidUntil")).matches("\\d{14}Z");
        assertThat(modValue(step.mods(), "secPwdLastChanged")).matches("\\d{14}Z");
    }

    // ── grant (linked) ──────────────────────────────────────────────

    @Test
    void grantLinked_secUUID_compensatePolicy_withSecDnBackref() {
        AddStep step = plans.grantLinked(linkedConfig(), payload("uid=alice,ou=people,dc=x", "alice"));

        assertThat(step.onFailure()).isEqualTo(StepFailurePolicy.COMPENSATE);
        assertThat(step.targetDn())
                .startsWith("secUUID=")
                .endsWith(",secAuthority=Default,o=acme,c=us");
        assertThat(objectClassValues(step.attributes())).contains("top", "secUser");
        assertThat(attrValue(step.attributes(), "secDN")).isEqualTo("uid=alice,ou=people,dc=x");
        assertThat(attrValue(step.attributes(), "secLogin")).isEqualTo("alice");
    }

    @Test
    void grantLinked_secLoginRdn_usesUidAsRdnValue() {
        VendorIntegrationIsvaConfig cfg = linkedConfig();
        cfg.setSecuserRdnAttribute("secLogin");

        AddStep step = plans.grantLinked(cfg, payload("uid=alice,dc=x", "alice"));

        assertThat(step.targetDn())
                .startsWith("secLogin=alice,")
                .endsWith(",secAuthority=Default,o=acme,c=us");
        assertThat(attrValue(step.attributes(), "secLogin")).isEqualTo("alice");
    }

    @Test
    void grantLinked_unsupportedRdnAttribute_throws() {
        VendorIntegrationIsvaConfig cfg = linkedConfig();
        cfg.setSecuserRdnAttribute("cn");

        assertThatThrownBy(() -> plans.grantLinked(cfg, payload("uid=alice,dc=x", "alice")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported secuser_rdn_attribute");
    }

    // ── revoke ──────────────────────────────────────────────────────

    @Test
    void disable_replacesAcctValidFalse_andExpiresValidUntil() {
        ModifyStep step = plans.disable("secUUID=abc,secAuthority=Default,o=acme,c=us");

        assertThat(step.targetDn()).isEqualTo("secUUID=abc,secAuthority=Default,o=acme,c=us");
        assertThat(step.mods()).allMatch(m -> m.getModificationType() == ModificationType.REPLACE);
        assertThat(modValue(step.mods(), "secAcctValid")).isEqualTo("FALSE");
        assertThat(modValue(step.mods(), "secValidUntil")).matches("\\d{14}Z");
    }

    @Test
    void hardDelete_isDeleteStepOnDn() {
        DeleteStep step = plans.hardDelete("secUUID=abc,o=acme,c=us");
        assertThat(step.targetDn()).isEqualTo("secUUID=abc,o=acme,c=us");
    }

    // ── account verbs ───────────────────────────────────────────────

    @Test
    void suspend_replacesAcctValidFalse_only_leavingValidUntilUntouched() {
        ModifyStep step = plans.suspend("uid=alice,dc=x");
        assertThat(step.mods()).singleElement()
                .satisfies(m -> {
                    assertThat(m.getModificationType()).isEqualTo(ModificationType.REPLACE);
                    assertThat(m.getAttributeName()).isEqualTo("secAcctValid");
                    assertThat(m.getValues()[0]).isEqualTo("FALSE");
                });
    }

    @Test
    void restore_replacesAcctValidTrue() {
        ModifyStep step = plans.restore("uid=alice,dc=x");
        assertThat(modValue(step.mods(), "secAcctValid")).isEqualTo("TRUE");
        assertThat(step.mods()).hasSize(1);
    }

    @Test
    void renew_replacesValidUntil_toSuppliedInstant() {
        ModifyStep step = plans.renew("uid=alice,dc=x", Instant.parse("2030-01-02T03:04:05Z"));
        assertThat(modValue(step.mods(), "secValidUntil")).isEqualTo("20300102030405Z");
    }

    @Test
    void forceCredentialReset_replacesPwdValidFalse() {
        ModifyStep step = plans.forceCredentialReset("uid=alice,dc=x");
        assertThat(modValue(step.mods(), "secPwdValid")).isEqualTo("FALSE");
        assertThat(step.mods()).hasSize(1);
    }

    @Test
    void generalizedTime_formatsUtcGeneralizedTime() {
        assertThat(IsvaSecUserPlans.generalizedTime(Instant.parse("2026-05-26T12:00:00Z")))
                .isEqualTo("20260526120000Z");
    }

    // ── helpers ─────────────────────────────────────────────────────

    private VendorIntegrationIsvaConfig inlineConfig() {
        VendorIntegrationIsvaConfig cfg = new VendorIntegrationIsvaConfig();
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

    private static UserCreatePayload payload(String dn, String uid) {
        return UserCreatePayload.of(dn, Map.of(
                "objectClass", List.of("inetOrgPerson"),
                "uid", List.of(uid)));
    }

    private static List<String> objectClassValues(List<Attribute> attrs) {
        return attrs.stream()
                .filter(a -> "objectClass".equalsIgnoreCase(a.getName()))
                .flatMap(a -> Arrays.stream(a.getValues()))
                .toList();
    }

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
