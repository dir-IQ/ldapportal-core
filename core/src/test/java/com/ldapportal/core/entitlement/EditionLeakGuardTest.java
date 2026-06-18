// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.entitlement;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioural half of the edition-leak guard (the structural half is
 * {@code com.ldapportal.architecture.CatalogExposureArchitectureTest}).
 *
 * <p>Discovers <em>every</em> {@link EditionScoped} catalogue enum by classpath
 * scan — so a future catalogue enum is covered automatically — and pins the
 * contract of the single exposure gate
 * ({@link EntitlementService#exposed(Class)}):</p>
 * <ul>
 *   <li>On the <b>community</b> edition, no entitlement-gated constant is exposed
 *       (the leak we keep hitting: access reviews, SoD, HR, the auditor portal),
 *       and every core constant still is.</li>
 *   <li>On a <b>fully-entitled</b> license, every constant is exposed (the filter
 *       doesn't over-hide).</li>
 * </ul>
 *
 * <p>If a new catalogue enum implements {@link EditionScoped} but mis-classifies
 * a constant, or the gate regresses, this fails before it can ship.</p>
 */
class EditionLeakGuardTest {

    /** Community baseline — no entitlements (functional interface: current()). */
    private final EntitlementService community = new CommunityEditionLicenseProvider()::current;

    /** Every entitlement granted — for the "doesn't over-hide" check. */
    private final EntitlementService fullyEntitled = () -> new License(
            null, Edition.ENTERPRISE, Set.of(Entitlement.values()),
            Map.of(), Instant.EPOCH, Instant.MAX, null);

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void communityEditionExposesNoEntitlementGatedCatalogueConstant() {
        List<Class<? extends EditionScoped>> catalogs = discoverCatalogEnums();

        // The scan must find the known catalogues — a zero result would mean the
        // guard silently covers nothing (e.g. discovery broke).
        assertThat(catalogs)
                .as("EditionScoped catalogue enums discovered on the classpath")
                .isNotEmpty();

        int gatedTotal = 0;
        for (Class<? extends EditionScoped> catalog : catalogs) {
            List<? extends EditionScoped> exposedInCommunity = community.exposed((Class) catalog);
            List<? extends EditionScoped> exposedFully = fullyEntitled.exposed((Class) catalog);

            for (EditionScoped constant : catalog.getEnumConstants()) {
                boolean gated = constant.requiredEntitlement() != null;
                if (gated) gatedTotal++;

                assertThat(exposedInCommunity.contains(constant))
                        .as("%s.%s exposed on community edition (gated=%s)",
                                catalog.getSimpleName(), ((Enum<?>) constant).name(), gated)
                        .isEqualTo(!gated);

                assertThat(exposedFully.contains(constant))
                        .as("%s.%s exposed when fully entitled",
                                catalog.getSimpleName(), ((Enum<?>) constant).name())
                        .isTrue();
            }
        }

        // The guard would be vacuous if nothing were ever gated — assert the
        // mechanism is actually exercised by real non-community constants.
        assertThat(gatedTotal)
                .as("entitlement-gated catalogue constants across all EditionScoped enums")
                .isPositive();
    }

    @SuppressWarnings("unchecked")
    private List<Class<? extends EditionScoped>> discoverCatalogEnums() {
        JavaClasses imported = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.ldapportal");
        List<Class<? extends EditionScoped>> catalogs = new ArrayList<>();
        for (JavaClass jc : imported) {
            if (jc.isAssignableTo(Enum.class) && jc.isAssignableTo(EditionScoped.class)) {
                catalogs.add((Class<? extends EditionScoped>) jc.reflect());
            }
        }
        return catalogs;
    }
}
