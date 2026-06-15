// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * Guards the directory-sync ↔ provisioning boundary.
 *
 * <p>The sync engine writes to target directories through <em>raw</em> LDAP
 * ({@code RecomputeEngine} via
 * {@code LdapConnectionFactory.withConnectionUnreplicated} → {@code conn.add/
 * modify/delete}), deliberately bypassing the provisioning interceptor chain.
 * That bypass is the reason a sync that creates a demographic entry on an
 * IVIA-enabled target does <b>not</b> trigger the IVIA addon's secUser
 * creation — the {@code ProvisioningInterceptor} simply never runs for
 * sync-provisioned entries.</p>
 *
 * <p>If the sync package were ever wired to the provisioning write SPI
 * (the interceptor chain, the plan executor, or the user/group write services
 * that drive them), vendor interceptors would start firing for every
 * sync-created entry — silently creating a secUser per synced demographic in
 * the IVIA→IVIA case. This rule fails on that change so the decision is made
 * consciously. See {@code docs/architecture/sync-provisioning-bypass.md}.</p>
 */
@AnalyzeClasses(packages = "com.ldapportal", importOptions = ImportOption.DoNotIncludeTests.class)
class SyncProvisioningBypassArchitectureTest {

    /**
     * The provisioning write SPI and the services that invoke it. Sync target
     * writes must not route through any of these — doing so would re-engage
     * vendor interceptors (e.g. IVIA secUser creation) for synced entries.
     */
    private static final Set<String> PROVISIONING_WRITE_SPI = Set.of(
            "com.ldapportal.core.provisioning.ProvisioningInterceptorChain",
            "com.ldapportal.core.provisioning.PlanExecutor",
            "com.ldapportal.ldap.LdapUserService",
            "com.ldapportal.ldap.LdapGroupService");

    private static final ArchCondition<JavaClass> NOT_DEPEND_ON_PROVISIONING_WRITE_SPI =
            new ArchCondition<>("not depend on the provisioning write SPI") {
                @Override
                public void check(JavaClass clazz, ConditionEvents events) {
                    for (Dependency dep : clazz.getDirectDependenciesFromSelf()) {
                        String target = dep.getTargetClass().getFullName();
                        if (PROVISIONING_WRITE_SPI.contains(target)) {
                            events.add(SimpleConditionEvent.violated(clazz,
                                    clazz.getFullName() + " depends on " + target + " ("
                                  + dep.getSourceCodeLocation() + "). Sync target writes must "
                                  + "stay on the raw-LDAP path so vendor interceptors (e.g. IVIA "
                                  + "secUser creation) don't fire for synced entries."));
                        }
                    }
                }
            };

    @ArchTest
    static final ArchRule sync_engine_bypasses_provisioning_write_spi =
            classes().that().resideInAPackage("com.ldapportal.ldap.sync..")
                    .should(NOT_DEPEND_ON_PROVISIONING_WRITE_SPI)
                    .because("Directory-sync target writes go through raw LDAP "
                           + "(RecomputeEngine via withConnectionUnreplicated), deliberately "
                           + "bypassing the provisioning interceptor chain. Routing sync through "
                           + "it would create a secUser for every sync-provisioned demographic on "
                           + "an IVIA-enabled target. See "
                           + "docs/architecture/sync-provisioning-bypass.md.");
}
