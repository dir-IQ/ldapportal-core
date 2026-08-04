// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva;

import com.ldapportal.addons.isva.entity.IsvaRdnValueSource;
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
        assertThat(attrValue(attrs, "secLoginType")).isEqualTo("Default");
        assertThat(attrValue(attrs, "secAuthority")).isEqualTo("Default");
        assertThat(attrValue(attrs, "secAcctValid")).isEqualTo("TRUE");
        assertThat(attrValue(attrs, "secPwdValid")).isEqualTo("TRUE");
        assertThat(attrValue(attrs, "secValidUntil")).matches("\\d{14}Z");
        assertThat(attrValue(attrs, "secPwdLastChanged")).matches("\\d{14}Z");
    }

    @Test
    void grantInline_addsConfiguredExtraObjectClasses() {
        VendorIntegrationIsvaConfig cfg = inlineConfig();
        cfg.setSecuserObjectClasses(List.of("secUser", "eUser"));

        List<Attribute> attrs = plans.grantInline(
                BaselinePlans.attributesFromMap(Map.of(
                        "objectClass", List.of("inetOrgPerson", "person"),
                        "uid", List.of("alice"))),
                cfg, payload("uid=alice,dc=x", "alice"));

        assertThat(objectClassValues(attrs))
                .containsExactlyInAnyOrder("inetOrgPerson", "person", "secUser", "eUser");
    }

    @Test
    void grantInline_honoursConfiguredSecLoginType() {
        VendorIntegrationIsvaConfig cfg = inlineConfig();
        cfg.setSecLoginType("Full");

        List<Attribute> attrs = plans.grantInline(
                BaselinePlans.attributesFromMap(Map.of(
                        "objectClass", List.of("inetOrgPerson"),
                        "uid", List.of("alice"))),
                cfg, payload("uid=alice,dc=x", "alice"));

        assertThat(attrValue(attrs, "secLoginType")).isEqualTo("Full");
    }

    @Test
    void grantInline_trimmedOverlay_omitsDisabledOptionalAttrs() {
        // A directory whose secUser schema has no secValidUntil / secLogin
        // trims them from the overlay; a grant must then not write them.
        VendorIntegrationIsvaConfig cfg = trimmedOverlayConfig();

        List<Attribute> attrs = plans.grantInline(
                BaselinePlans.attributesFromMap(Map.of(
                        "objectClass", List.of("inetOrgPerson"),
                        "uid", List.of("alice"))),
                cfg, payload("uid=alice,dc=x", "alice"));

        // Always-on MUST attrs still written.
        assertThat(attrValue(attrs, "secLoginType")).isEqualTo("Default");
        assertThat(attrValue(attrs, "secAuthority")).isEqualTo("Default");
        assertThat(attrValue(attrs, "secAcctValid")).isEqualTo("TRUE");
        // Trimmed optional attrs absent.
        assertThat(attrValue(attrs, "secLogin")).isNull();
        assertThat(attrValue(attrs, "secValidUntil")).isNull();
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
        assertThat(modValue(step.mods(), "secLoginType")).isEqualTo("Default");
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
        cfg.setSecuserRdnValueSource(IsvaRdnValueSource.UID);

        AddStep step = plans.grantLinked(cfg, payload("uid=alice,dc=x", "alice"));

        assertThat(step.targetDn())
                .startsWith("secLogin=alice,")
                .endsWith(",secAuthority=Default,o=acme,c=us");
        assertThat(attrValue(step.attributes(), "secLogin")).isEqualTo("alice");
    }

    @Test
    void grantLinked_freeFormRdnAttribute_withEUserObjectClass_andUidValueSource() {
        // The customer pattern: principalName=<uid>,<mgmt base>, where
        // principalName is contributed by the eUser objectClass.
        VendorIntegrationIsvaConfig cfg = linkedConfig();
        cfg.setSecuserObjectClasses(List.of("secUser", "eUser"));
        cfg.setSecuserRdnAttribute("principalName");
        cfg.setSecuserRdnValueSource(IsvaRdnValueSource.UID);

        AddStep step = plans.grantLinked(cfg, payload("uid=alice,ou=people,dc=x", "alice"));

        assertThat(step.targetDn())
                .startsWith("principalName=alice,")
                .endsWith(",secAuthority=Default,o=acme,c=us");
        assertThat(objectClassValues(step.attributes()))
                .containsExactlyInAnyOrder("top", "secUser", "eUser");
        // RDN attribute is mirrored as a value on the entry (LDAP requires it).
        assertThat(attrValue(step.attributes(), "principalName")).isEqualTo("alice");
        assertThat(attrValue(step.attributes(), "secDN")).isEqualTo("uid=alice,ou=people,dc=x");
    }

    @Test
    void grantLinked_freeFormRdnAttribute_withGeneratedValueSource() {
        // A non-stock RDN attribute name can still take a generated value.
        VendorIntegrationIsvaConfig cfg = linkedConfig();
        cfg.setSecuserRdnAttribute("cn");
        cfg.setSecuserRdnValueSource(IsvaRdnValueSource.GENERATED_UUID);

        AddStep step = plans.grantLinked(cfg, payload("uid=alice,dc=x", "alice"));

        // cn=<uuid>,… — a UUID value, not the uid.
        assertThat(step.targetDn())
                .startsWith("cn=")
                .doesNotContain("cn=alice")
                .endsWith(",secAuthority=Default,o=acme,c=us");
    }

    @Test
    void grantLinked_secUserAlwaysPresent_evenWhenOmittedFromConfig() {
        // secUser is load-bearing (lookup / probe filter on it); the
        // plan re-adds it if the operator dropped it from the list.
        VendorIntegrationIsvaConfig cfg = linkedConfig();
        cfg.setSecuserObjectClasses(List.of("eUser"));

        AddStep step = plans.grantLinked(cfg, payload("uid=alice,dc=x", "alice"));

        assertThat(objectClassValues(step.attributes())).contains("secUser", "eUser");
    }

    // ── revoke ──────────────────────────────────────────────────────

    @Test
    void disable_replacesAcctValidFalse_andExpiresValidUntil() {
        ModifyStep step = plans.disable("secUUID=abc,secAuthority=Default,o=acme,c=us",
                inlineConfig());

        assertThat(step.targetDn()).isEqualTo("secUUID=abc,secAuthority=Default,o=acme,c=us");
        assertThat(step.mods()).allMatch(m -> m.getModificationType() == ModificationType.REPLACE);
        assertThat(modValue(step.mods(), "secAcctValid")).isEqualTo("FALSE");
        assertThat(modValue(step.mods(), "secValidUntil")).matches("\\d{14}Z");
    }

    @Test
    void disable_omitsSecValidUntil_whenNotInOverlay() {
        VendorIntegrationIsvaConfig cfg = inlineConfig();
        cfg.setSecuserOverlayAttributes(List.of("secLogin", "secAcctValid", "secPwdValid"));

        ModifyStep step = plans.disable("uid=alice,dc=x", cfg);

        assertThat(modValue(step.mods(), "secAcctValid")).isEqualTo("FALSE");
        // secValidUntil isn't part of this directory's overlay → not written,
        // so soft-disable doesn't trip an "attribute not allowed" rejection.
        assertThat(step.mods())
                .noneMatch(m -> m.getAttributeName().equalsIgnoreCase("secValidUntil"));
    }

    @Test
    void hardDelete_isDeleteStepOnDn() {
        DeleteStep step = plans.hardDelete("secUUID=abc,o=acme,c=us");
        assertThat(step.targetDn()).isEqualTo("secUUID=abc,o=acme,c=us");
    }

    /**
     * {@code writtenOverlayAttrNames} is the single source of truth for
     * both what a grant writes and what an inline hard-revoke strips.
     * This pins that a grant's actual MODIFY-ADD mods enumerate exactly
     * that list, so the two can never drift — for the full-overlay default
     * and for a trimmed overlay alike.
     */
    @Test
    void writtenOverlayAttrNames_matchesEveryKey_aGrantWrites() {
        for (VendorIntegrationIsvaConfig cfg
                : List.of(inlineConfig(), trimmedOverlayConfig())) {
            ModifyStep grant = plans.grantInlineOnExisting("uid=alice,dc=x", cfg, "alice");
            java.util.Set<String> grantSecAttrs = new java.util.HashSet<>();
            for (Modification m : grant.mods()) {
                String name = m.getAttributeName();
                if ("objectClass".equalsIgnoreCase(name)) continue;
                grantSecAttrs.add(name);
            }
            assertThat(grantSecAttrs)
                    .as("A grant must write exactly writtenOverlayAttrNames(cfg) so "
                            + "revokeInlineOnExisting strips the same set.")
                    .isEqualTo(new java.util.HashSet<>(
                            IsvaSecUserPlans.writtenOverlayAttrNames(cfg)));
        }
    }

    @Test
    void revokeInlineOnExisting_stripsEveryOverlayAttr_andSecUserObjectClass() {
        ModifyStep step = plans.revokeInlineOnExisting("uid=alice,dc=x", inlineConfig());

        assertThat(step.targetDn()).isEqualTo("uid=alice,dc=x");
        // Every modification is DELETE — we're tearing down the overlay.
        assertThat(step.mods()).allMatch(
                m -> m.getModificationType() == ModificationType.DELETE);
        // objectClass: secUser DELETE specifically (preserves other
        // objectClass values on the entry).
        assertThat(step.mods()).anySatisfy(m -> {
            assertThat(m.getAttributeName()).isEqualToIgnoringCase("objectClass");
            assertThat(m.getValues()).contains("secUser");
        });
        // Every overlay attribute is removed.
        java.util.Set<String> deletedAttrs = new java.util.HashSet<>();
        for (Modification m : step.mods()) {
            if (!"objectClass".equalsIgnoreCase(m.getAttributeName())) {
                deletedAttrs.add(m.getAttributeName());
            }
        }
        assertThat(deletedAttrs)
                .containsExactlyInAnyOrderElementsOf(
                        IsvaSecUserPlans.writtenOverlayAttrNames(inlineConfig()));
    }

    @Test
    void revokeInlineOnExisting_stripsConfiguredExtraObjectClasses() {
        VendorIntegrationIsvaConfig cfg = inlineConfig();
        cfg.setSecuserObjectClasses(List.of("secUser", "eUser"));

        ModifyStep step = plans.revokeInlineOnExisting("uid=alice,dc=x", cfg);

        // The objectClass DELETE removes the same set grant added.
        assertThat(step.mods()).anySatisfy(m -> {
            assertThat(m.getAttributeName()).isEqualToIgnoringCase("objectClass");
            assertThat(m.getValues()).contains("secUser", "eUser");
        });
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

    /** Inline config whose overlay omits secLogin + secValidUntil — the
     * shape a deployment whose secUser schema lacks them would use. */
    private VendorIntegrationIsvaConfig trimmedOverlayConfig() {
        VendorIntegrationIsvaConfig cfg = inlineConfig();
        cfg.setSecuserOverlayAttributes(List.of(
                "secAcctValid", "secPwdValid", "secPwdLastChanged"));
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
