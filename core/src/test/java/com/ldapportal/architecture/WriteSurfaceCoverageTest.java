// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.architecture;

import com.ldapportal.ldap.annotation.LdapWriteAuthorized;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
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
 * Enforces that every class issuing a direct mutating UnboundID call
 * ({@code add}/{@code modify}/{@code delete}/{@code modifyDN}) carries the
 * {@link LdapWriteAuthorized} marker. Direct writes must originate from an
 * enumerated chokepoint so capture (replication, audit) is reliable.
 *
 * <p>The single-page inventory of every annotated site lives at
 * {@code docs/architecture/ldap-write-surface.md} — keep the two in sync.
 *
 * <p><b>Note on rule shape (deviation from the migration plan's sketch).</b>
 * The plan composed the predicate from ArchUnit's {@code target(...)} /
 * {@code owner(assignableTo(...))} static-import helpers and then itself
 * flagged those as brittle. This expresses the same rule ("a class that
 * issues a mutating call whose target owner is an UnboundID LDAP interface
 * must be {@code @LdapWriteAuthorized}") with a plain {@link ArchCondition}
 * that walks each class's outgoing method calls — far less fragile. It is a
 * <em>transitional</em> constraint: R6 will additionally require that
 * {@code LdapWriteAuthorized} classes are themselves reachable only via
 * {@code PlanExecutor}. For now the annotation marks the inventory; R6
 * narrows it.
 */
@AnalyzeClasses(packages = "com.ldapportal", importOptions = ImportOption.DoNotIncludeTests.class)
class WriteSurfaceCoverageTest {

    private static final Set<String> MUTATING_METHODS =
            Set.of("add", "modify", "delete", "modifyDN");

    /** A call to a mutating method whose target owner is an UnboundID LDAP interface. */
    private static boolean isMutatingLdapCall(JavaMethodCall call) {
        if (!MUTATING_METHODS.contains(call.getTarget().getName())) {
            return false;
        }
        JavaClass owner = call.getTargetOwner();
        // Match by owner type: the UnboundID write surface (LDAPInterface
        // and everything assignable to it — LDAPConnection,
        // FullLDAPInterface, LDAPConnectionPool). Falling back to the
        // package guards against the SDK hierarchy not being fully resolved
        // at import time.
        return owner.isAssignableTo("com.unboundid.ldap.sdk.LDAPInterface")
                || owner.getName().startsWith("com.unboundid.ldap.sdk.");
    }

    private static final ArchCondition<JavaClass> BE_AUTHORIZED_IF_ISSUING_MUTATING_LDAP_CALLS =
            new ArchCondition<>("be @LdapWriteAuthorized if they issue mutating UnboundID calls") {
                @Override
                public void check(JavaClass clazz, ConditionEvents events) {
                    if (clazz.isAnnotatedWith(LdapWriteAuthorized.class)
                            || clazz.isMetaAnnotatedWith(LdapWriteAuthorized.class)) {
                        return;
                    }
                    for (JavaMethodCall call : clazz.getMethodCallsFromSelf()) {
                        if (isMutatingLdapCall(call)) {
                            events.add(SimpleConditionEvent.violated(clazz,
                                    clazz.getFullName() + " issues a mutating UnboundID call ("
                                  + call.getTarget().getName() + " at "
                                  + call.getSourceCodeLocation()
                                  + ") but is not @LdapWriteAuthorized"));
                        }
                    }
                }
            };

    @ArchTest
    static final ArchRule mutating_ldap_calls_only_from_authorized_chokepoints =
            classes().should(BE_AUTHORIZED_IF_ISSUING_MUTATING_LDAP_CALLS)
                    .because("Direct UnboundID writes must originate from an "
                           + "@LdapWriteAuthorized chokepoint so capture (replication, "
                           + "audit) is reliable. See docs/architecture/ldap-write-surface.md. "
                           + "Tighten further in R6.");
}
