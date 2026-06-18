// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.architecture;

import com.ldapportal.core.entitlement.EditionLeakGuards;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Structural half of the edition-leak guard for core (the behavioural half is
 * {@code com.ldapportal.core.entitlement.EditionLeakGuardTest}).
 *
 * <p>Controllers must not enumerate an {@code EditionScoped} catalogue enum
 * directly — they must route exposure through
 * {@code EntitlementService.exposed(...)}, so entitlement-gated constants can't
 * leak to the UI. The rule is shared from {@link EditionLeakGuards} so the same
 * enforcement applies in downstream modules over their own classpath.</p>
 */
@AnalyzeClasses(packages = "com.ldapportal", importOptions = {
        ImportOption.DoNotIncludeTests.class,
        EditionLeakGuards.ExcludeTestArtifacts.class })
class CatalogExposureArchitectureTest {

    @ArchTest
    static final ArchRule controllers_expose_catalogue_enums_through_the_entitlement_gate =
            EditionLeakGuards.CONTROLLERS_EXPOSE_CATALOGUES_THROUGH_THE_ENTITLEMENT_GATE;
}
