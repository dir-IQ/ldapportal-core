// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva;

import com.ldapportal.addons.isva.entity.IsvaTopologyMode;
import com.ldapportal.addons.isva.entity.SecUserAttribute;
import com.ldapportal.addons.isva.entity.SecUserAttributeValueKind;
import com.ldapportal.addons.isva.entity.VendorIntegrationIsvaConfig;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The unified per-attribute model: the legacy deriver (behaviour-preservation)
 * and the expression-driven resolver (literals, computed values, cross-attribute
 * dependency resolution, and its failure modes).
 */
class IsvaSecUserModelTest {

    private static final Instant NOW = Instant.parse("2020-01-02T03:04:05Z");
    private static final Map<String, List<String>> ALICE = Map.of("uid", List.of("alice"));

    private static VendorIntegrationIsvaConfig legacyDefault() {
        VendorIntegrationIsvaConfig cfg = new VendorIntegrationIsvaConfig();
        cfg.setTopologyMode(IsvaTopologyMode.INLINE);
        // Defaults: secLoginType=Default, secAuthority=Default,
        // defaultValidUntilYears=100, overlay = the 5-attr default set,
        // secuserAttributes = null (not migrated).
        return cfg;
    }

    // ── legacy deriver: behaviour-preserving model ─────────────────────

    @Test
    void deriveFromLegacy_default_hasRequiredAlwaysOn_identityAttrsOff() {
        List<SecUserAttribute> model = IsvaSecUserPlans.deriveFromLegacy(legacyDefault());

        // Canonical order, all ten known attributes present.
        assertThat(model).extracting(SecUserAttribute::name).containsExactly(
                "secLogin", "secLoginType", "secAuthority", "secAcctValid",
                "secPwdValid", "secValidUntil", "secPwdLastChanged",
                "secUUID", "principalName", "secDomainId");

        // Required MUST attrs are on and literal.
        assertThat(byName(model, "secLoginType"))
                .returns(true, SecUserAttribute::enabled)
                .returns(SecUserAttributeValueKind.LITERAL, SecUserAttribute::valueKind)
                .returns("Default", SecUserAttribute::value);
        assertThat(byName(model, "secAuthority").enabled()).isTrue();

        // Default overlay members on; identity attrs off.
        assertThat(byName(model, "secLogin").enabled()).isTrue();
        assertThat(byName(model, "secValidUntil").enabled()).isTrue();
        assertThat(byName(model, "secUUID").enabled()).isFalse();
        assertThat(byName(model, "principalName").enabled()).isFalse();
        assertThat(byName(model, "secDomainId").enabled()).isFalse();

        // Computed defaults carry the expected expressions.
        assertThat(byName(model, "secLogin").value()).isEqualTo("${user.uid}");
        assertThat(byName(model, "secValidUntil").value()).isEqualTo("nowPlusYears(100)");
        assertThat(byName(model, "secPwdLastChanged").value()).isEqualTo("now()");
        assertThat(byName(model, "secUUID").value()).isEqualTo("uuid()");
        assertThat(byName(model, "secDomainId").value())
                .isEqualTo("${sec.secAuthority}%${user.uid}");
    }

    @Test
    void deriveFromLegacy_customValues_flowThrough() {
        VendorIntegrationIsvaConfig cfg = legacyDefault();
        cfg.setSecLoginType("Federated");
        cfg.setSecAuthority("EUR");
        cfg.setDefaultValidUntilYears(5);

        List<SecUserAttribute> model = IsvaSecUserPlans.deriveFromLegacy(cfg);
        assertThat(byName(model, "secLoginType").value()).isEqualTo("Federated");
        assertThat(byName(model, "secAuthority").value()).isEqualTo("EUR");
        assertThat(byName(model, "secValidUntil").value()).isEqualTo("nowPlusYears(5)");
    }

    @Test
    void effectiveAttributes_prefersStoredModelOverLegacy() {
        VendorIntegrationIsvaConfig cfg = legacyDefault();
        List<SecUserAttribute> stored = List.of(
                new SecUserAttribute("secLoginType", true, SecUserAttributeValueKind.LITERAL, "X"));
        cfg.setSecuserAttributes(stored);

        assertThat(IsvaSecUserPlans.effectiveAttributes(cfg)).isSameAs(stored);
    }

    // ── resolver ───────────────────────────────────────────────────────

    @Test
    void resolveValues_derivedDefault_producesConcreteValues() {
        Map<String, String> values =
                IsvaSecUserPlans.resolveValues(legacyDefault(), ALICE, NOW);

        assertThat(values.get("secLogin")).isEqualTo("alice");
        assertThat(values.get("secLoginType")).isEqualTo("Default");
        assertThat(values.get("secAuthority")).isEqualTo("Default");
        assertThat(values.get("secAcctValid")).isEqualTo("TRUE");
        assertThat(values.get("secPwdLastChanged")).isEqualTo("20200102030405Z");
        assertThat(values.get("secValidUntil")).matches("\\d{14}Z");
        // Disabled identity attrs aren't resolved.
        assertThat(values).doesNotContainKeys("secUUID", "principalName", "secDomainId");
    }

    @Test
    void resolveValues_dependencyResolution_secDomainIdDependsOnSecAuthority() {
        // secDomainId (computed) references secAuthority (also in the model) and
        // the base uid — resolved in dependency order regardless of list order.
        VendorIntegrationIsvaConfig cfg = legacyDefault();
        cfg.setSecuserAttributes(List.of(
                new SecUserAttribute("secDomainId", true, SecUserAttributeValueKind.COMPUTED,
                        "${sec.secAuthority}%${user.uid}"),
                new SecUserAttribute("secAuthority", true, SecUserAttributeValueKind.LITERAL,
                        "Corp")));

        Map<String, String> values = IsvaSecUserPlans.resolveValues(cfg, ALICE, NOW);
        assertThat(values.get("secDomainId")).isEqualTo("Corp%alice");
    }

    @Test
    void resolveValues_referenceToDisabledAttr_throws() {
        VendorIntegrationIsvaConfig cfg = legacyDefault();
        cfg.setSecuserAttributes(List.of(
                new SecUserAttribute("secAuthority", true, SecUserAttributeValueKind.LITERAL, "Corp"),
                new SecUserAttribute("principalName", false, SecUserAttributeValueKind.COMPUTED, "${user.uid}"),
                new SecUserAttribute("secDomainId", true, SecUserAttributeValueKind.COMPUTED,
                        "${sec.secAuthority}%${sec.principalName}")));

        assertThatThrownBy(() -> IsvaSecUserPlans.resolveValues(cfg, ALICE, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("principalName")
                .hasMessageContaining("not a written attribute");
    }

    @Test
    void resolveValues_cyclicReferences_throw() {
        VendorIntegrationIsvaConfig cfg = legacyDefault();
        cfg.setSecuserAttributes(List.of(
                new SecUserAttribute("secLoginType", true, SecUserAttributeValueKind.COMPUTED, "${sec.secAuthority}"),
                new SecUserAttribute("secAuthority", true, SecUserAttributeValueKind.COMPUTED, "${sec.secLoginType}")));

        assertThatThrownBy(() -> IsvaSecUserPlans.resolveValues(cfg, ALICE, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cyclic");
    }

    private static SecUserAttribute byName(List<SecUserAttribute> model, String name) {
        return model.stream().filter(a -> a.name().equals(name)).findFirst().orElseThrow();
    }
}
