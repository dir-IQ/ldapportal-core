// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.entitlement;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method or class as requiring a specific {@link Entitlement} to
 * execute. The {@link EntitlementAspect} intercepts annotated code and calls
 * {@link EntitlementService#requireEntitlement(Entitlement)}; a missing
 * entitlement short-circuits the call with an
 * {@link EntitlementMissingException}, which the global exception handler
 * translates to HTTP 402.
 *
 * <p>Use at the <b>class</b> level on a controller or service to gate every
 * entry point at once — typical for a whole ee module:</p>
 *
 * <pre>{@code
 * @Entitled(Entitlement.GOVERNANCE)
 * @RestController
 * public class SodPolicyController { ... }
 * }</pre>
 *
 * <p>Use at the <b>method</b> level for finer-grained control. A method
 * annotation overrides any class-level annotation.</p>
 *
 * <p>Distinct from {@link com.ldapportal.auth.RequiresFeature}, which is
 * per-user authorization ("can this admin delete users?"). {@code @Entitled}
 * is per-tenant licensing ("did this customer buy this feature?"). The two
 * can be combined — both checks must pass.</p>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Entitled {
    Entitlement value();
}
