// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.architecture;

import com.ldapportal.core.entitlement.EditionScoped;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * Structural half of the edition-leak guard (the behavioural half is
 * {@code com.ldapportal.core.entitlement.EditionLeakGuardTest}).
 *
 * <p>Several UI surfaces enumerate a catalogue enum and ship the full value set
 * to the client. When some constants are edition-gated (access reviews, SoD, HR
 * sync, the auditor portal — see {@link EditionScoped}), forgetting to filter
 * leaks them into the community app. The fix is to route every such enumeration
 * through {@code EntitlementService.exposed(...)}, which consults
 * {@link EditionScoped#requiredEntitlement()}.</p>
 *
 * <p>This rule makes the bypass impossible to ship by accident: a controller —
 * the serialisation boundary where catalogues reach the client — must not call
 * {@code values()} directly on an {@link EditionScoped} enum. Use
 * {@code entitlementService.exposed(MyEnum.class)} instead. Internal services and
 * the enums' own {@code fromDbValue}/converters legitimately enumerate all
 * values and are out of scope; they don't serialise to the wire.</p>
 */
@AnalyzeClasses(packages = "com.ldapportal", importOptions = ImportOption.DoNotIncludeTests.class)
class CatalogExposureArchitectureTest {

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

    @ArchTest
    static final ArchRule controllers_expose_catalogue_enums_through_the_entitlement_gate =
            classes().that().resideInAPackage("..controller..")
                    .should(NOT_ENUMERATE_EDITION_SCOPED_CATALOGUE)
                    .because("enumerating a catalogue enum and serialising the full value set leaks "
                           + "entitlement-gated constants (access reviews, SoD, HR, auditor portal) into the "
                           + "community app; route them through EntitlementService.exposed(...) instead. "
                           + "See EditionScoped.");
}
