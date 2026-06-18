// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.entitlement;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reusable edition-leak guards, shipped in core's {@code test-jar} so downstream
 * modules (the {@code ee} edition, the assembled distributions) can enforce the
 * same rules over <em>their own</em> classpath without copying the logic.
 *
 * <p>The two guards together stop non-entitled {@link EditionScoped} catalogue
 * constants (access reviews, SoD, HR sync, the auditor portal, …) from leaking
 * into a lower edition:</p>
 * <ul>
 *   <li>{@link #CONTROLLERS_EXPOSE_CATALOGUES_THROUGH_THE_ENTITLEMENT_GATE}
 *       (structural, ArchUnit) — a controller must not call {@code values()}
 *       directly on an {@link EditionScoped} enum; it must go through
 *       {@link EntitlementService#exposed(Class)}.</li>
 *   <li>{@link #assertCommunityHidesAllGatedConstants(String)} (behavioural) —
 *       discovers every {@link EditionScoped} enum on the classpath and asserts
 *       the community edition exposes no entitlement-gated constant (and a
 *       fully-entitled license exposes all of them).</li>
 * </ul>
 *
 * <p><b>Why a shared helper.</b> The guards in core's own test sources only see
 * core classes — core's tests don't run against modules that depend on core. A
 * downstream module enforces the same contract with a few lines:</p>
 * <pre>{@code
 * @AnalyzeClasses(packages = "com.ldapportal", importOptions = ImportOption.DoNotIncludeTests.class)
 * class MyEditionCatalogueExposureTest {
 *     @ArchTest static final ArchRule rule =
 *         EditionLeakGuards.CONTROLLERS_EXPOSE_CATALOGUES_THROUGH_THE_ENTITLEMENT_GATE;
 * }
 *
 * class MyEditionLeakGuardTest {
 *     @Test void noLeak() { EditionLeakGuards.assertCommunityHidesAllGatedConstants("com.ldapportal"); }
 * }
 * }</pre>
 */
public final class EditionLeakGuards {

    private EditionLeakGuards() {}

    /**
     * {@link ImportOption} that excludes test artifacts — core's {@code test-jar}
     * (where this very helper ships) and {@code target/test-classes} — so a
     * downstream {@code @AnalyzeClasses} guard analyses <em>production</em> code
     * only. {@link ImportOption.Predefined#DO_NOT_INCLUDE_TESTS} keys off the
     * {@code /test-classes/} path and so doesn't recognise test classes once
     * they're packaged into a {@code *-tests.jar} on the classpath; this fills
     * that gap. Pair it with {@code DoNotIncludeTests} in {@code @AnalyzeClasses}.
     */
    public static final class ExcludeTestArtifacts implements ImportOption {
        @Override
        public boolean includes(Location location) {
            return !location.contains("-tests.jar")
                && !location.contains("/test-classes/")
                && !location.contains("\\test-classes\\");
        }
    }

    /** Community baseline — no entitlements ({@link EntitlementService} is a
     *  functional interface over {@code current()}). */
    private static final EntitlementService COMMUNITY =
            new CommunityEditionLicenseProvider()::current;

    /** Every entitlement granted — for the "doesn't over-hide" check. */
    private static final EntitlementService FULLY_ENTITLED = () -> new License(
            null, Edition.ENTERPRISE, Set.of(Entitlement.values()),
            Map.of(), Instant.EPOCH, Instant.MAX, null);

    // ── Structural guard ────────────────────────────────────────────────────

    private static final ArchCondition<JavaClass> NOT_ENUMERATE_EDITION_SCOPED_CATALOGUE =
            new ArchCondition<>("not call values() directly on an EditionScoped catalogue enum") {
                @Override
                public void check(JavaClass clazz, ConditionEvents events) {
                    for (JavaMethodCall call : clazz.getMethodCallsFromSelf()) {
                        JavaClass owner = call.getTargetOwner();
                        if ("values".equals(call.getName()) && owner.isAssignableTo(EditionScoped.class)) {
                            events.add(SimpleConditionEvent.violated(clazz,
                                    clazz.getFullName() + " calls " + owner.getSimpleName()
                                  + ".values() (" + call.getSourceCodeLocation() + "). Controllers must "
                                  + "expose catalogue enums via EntitlementService.exposed(" + owner.getSimpleName()
                                  + ".class) so entitlement-gated values can't leak to the UI."));
                        }
                    }
                }
            };

    /**
     * Controllers may not enumerate an {@link EditionScoped} catalogue enum
     * directly; they must route exposure through
     * {@link EntitlementService#exposed(Class)}. Use as a shared
     * {@code @ArchTest} rule in a {@code @AnalyzeClasses} test.
     */
    public static final ArchRule CONTROLLERS_EXPOSE_CATALOGUES_THROUGH_THE_ENTITLEMENT_GATE =
            classes().that().resideInAPackage("..controller..")
                    .should(NOT_ENUMERATE_EDITION_SCOPED_CATALOGUE)
                    .because("enumerating a catalogue enum and serialising the full value set leaks "
                           + "entitlement-gated constants (access reviews, SoD, HR, auditor portal) into a "
                           + "lower edition; route them through EntitlementService.exposed(...) instead. "
                           + "See EditionScoped.");

    // ── Behavioural guard ───────────────────────────────────────────────────

    /**
     * Discover every {@link EditionScoped} enum under {@code basePackage} on the
     * current classpath (dependency jars included).
     */
    @SuppressWarnings("unchecked")
    public static List<Class<? extends EditionScoped>> discoverCatalogueEnums(String basePackage) {
        List<Class<? extends EditionScoped>> catalogs = new ArrayList<>();
        for (JavaClass jc : new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(new ExcludeTestArtifacts())
                .importPackages(basePackage)) {
            if (jc.isAssignableTo(Enum.class) && jc.isAssignableTo(EditionScoped.class)) {
                catalogs.add((Class<? extends EditionScoped>) jc.reflect());
            }
        }
        return catalogs;
    }

    /**
     * Assert that, on the community edition, no entitlement-gated
     * {@link EditionScoped} constant under {@code basePackage} is exposed by the
     * {@link EntitlementService#exposed(Class) exposure gate}, every core
     * constant still is, and a fully-entitled license exposes all of them.
     *
     * <p>Assumes core (which always carries gated constants) is on the
     * classpath, so the discovery is non-empty and the mechanism is exercised.</p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void assertCommunityHidesAllGatedConstants(String basePackage) {
        List<Class<? extends EditionScoped>> catalogs = discoverCatalogueEnums(basePackage);

        assertThat(catalogs)
                .as("EditionScoped catalogue enums discovered under %s", basePackage)
                .isNotEmpty();

        int gatedTotal = 0;
        for (Class<? extends EditionScoped> catalog : catalogs) {
            List<? extends EditionScoped> exposedInCommunity = COMMUNITY.exposed((Class) catalog);
            List<? extends EditionScoped> exposedFully = FULLY_ENTITLED.exposed((Class) catalog);

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

        assertThat(gatedTotal)
                .as("entitlement-gated catalogue constants discovered under %s", basePackage)
                .isPositive();
    }
}
