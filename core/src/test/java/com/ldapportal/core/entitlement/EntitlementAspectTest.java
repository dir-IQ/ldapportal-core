// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.entitlement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies {@link EntitlementAspect} fires on method-level and class-level
 * {@link Entitled} annotations and that method-level wins when both are
 * present. Uses Spring's {@link AspectJProxyFactory} to apply the aspect
 * to a plain target without booting the full Spring context — faster and
 * avoids the Postgres dependency of the smoke test.
 */
@ExtendWith(MockitoExtension.class)
class EntitlementAspectTest {

    @Mock private EntitlementService entitlementService;
    private EntitlementAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new EntitlementAspect(entitlementService);
    }

    @Test
    void fires_onMethodLevelAnnotation() {
        doThrowWhenMissing(Entitlement.GOVERNANCE);

        PlainTarget proxy = proxy(new PlainTarget());

        assertThatThrownBy(proxy::governanceMethod)
                .isInstanceOf(EntitlementMissingException.class);
    }

    @Test
    void fires_onClassLevelAnnotation() {
        doThrowWhenMissing(Entitlement.HYBRID);

        HybridTarget proxy = proxy(new HybridTarget());

        assertThatThrownBy(proxy::anyMethod)
                .isInstanceOf(EntitlementMissingException.class);
    }

    @Test
    void methodLevel_overridesClassLevel() {
        // Target has @Entitled(HYBRID) on the class and @Entitled(EVENTS)
        // on the method. The method-level annotation should win.
        doThrowWhenMissing(Entitlement.EVENTS);

        MixedTarget proxy = proxy(new MixedTarget());

        assertThatThrownBy(proxy::overriddenMethod)
                .isInstanceOf(EntitlementMissingException.class)
                .matches(ex -> ((EntitlementMissingException) ex).getEntitlement()
                        == Entitlement.EVENTS);
    }

    @Test
    void doesNotFire_whenEntitlementPresent() {
        // No stubs: the default entitlementService.requireEntitlement is a
        // no-op (doesn't throw), so the method runs normally.
        PlainTarget proxy = proxy(new PlainTarget());

        assertThat(proxy.governanceMethod()).isEqualTo("ok");
    }

    @Test
    void unannotatedMethods_onClassWithoutAnnotation_pass() {
        // Sanity: unannotated method in unannotated class isn't intercepted.
        PlainTarget proxy = proxy(new PlainTarget());

        assertThat(proxy.plainMethod()).isEqualTo("plain");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private <T> T proxy(T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        return (T) factory.getProxy();
    }

    private void doThrowWhenMissing(Entitlement e) {
        org.mockito.Mockito.doThrow(new EntitlementMissingException(e, Edition.COMMUNITY))
                .when(entitlementService).requireEntitlement(e);
    }

    // ── Test targets ────────────────────────────────────────────────────────

    /** Class without @Entitled; one method has @Entitled. */
    static class PlainTarget {
        @Entitled(Entitlement.GOVERNANCE)
        public String governanceMethod() { return "ok"; }

        public String plainMethod() { return "plain"; }
    }

    /** Class with @Entitled — applies to every method. */
    @Entitled(Entitlement.HYBRID)
    static class HybridTarget {
        public String anyMethod() { return "ok"; }
    }

    /** Class with @Entitled(HYBRID); one method overrides with @Entitled(EVENTS). */
    @Entitled(Entitlement.HYBRID)
    static class MixedTarget {
        @Entitled(Entitlement.EVENTS)
        public String overriddenMethod() { return "ok"; }
    }
}
