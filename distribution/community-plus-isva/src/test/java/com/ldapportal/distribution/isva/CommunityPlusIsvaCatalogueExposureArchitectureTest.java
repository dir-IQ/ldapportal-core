// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.distribution.isva;

import com.ldapportal.core.entitlement.EditionLeakGuards;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Edition-leak structural guard at the community-plus-ISVA distribution boundary.
 *
 * <p>Core's own guard only sees core classes. Run over the assembled classpath
 * (core + the ISVA addon), this catches an addon controller that enumerates an
 * {@code EditionScoped} catalogue enum directly instead of going through
 * {@code EntitlementService.exposed(...)}. The shared rule comes from core's
 * test-jar; see {@link EditionLeakGuards}.</p>
 */
@AnalyzeClasses(packages = "com.ldapportal", importOptions = {
        ImportOption.DoNotIncludeTests.class,
        EditionLeakGuards.ExcludeTestArtifacts.class })
class CommunityPlusIsvaCatalogueExposureArchitectureTest {

    @ArchTest
    static final ArchRule controllers_expose_catalogue_enums_through_the_entitlement_gate =
            EditionLeakGuards.CONTROLLERS_EXPOSE_CATALOGUES_THROUGH_THE_ENTITLEMENT_GATE;
}
