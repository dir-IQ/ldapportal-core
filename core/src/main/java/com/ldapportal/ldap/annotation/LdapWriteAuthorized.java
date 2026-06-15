// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class (or method) as an <em>approved chokepoint</em> for
 * issuing mutating LDAP calls ({@code add}/{@code modify}/{@code delete}/
 * {@code modifyDN}) against the UnboundID SDK.
 *
 * <p>Every site that calls a mutating UnboundID method directly must bear
 * this marker; {@code WriteSurfaceCoverageTest} fails the build otherwise.
 * Capture (replication, audit) is only reliable if the write surface is a
 * known, enumerated set — this annotation <em>is</em> that enumeration,
 * cross-referenced by {@code docs/architecture/ldap-write-surface.md}.
 *
 * <p><b>Transitional (R1b).</b> Today the marker only asserts "this is a
 * known write site". R6 will additionally require that annotated classes
 * are reachable only via {@code PlanExecutor}; for now the annotation
 * inventories the surface and R6 narrows it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface LdapWriteAuthorized {

    /**
     * Optional one-line justification for this chokepoint, mirrored in
     * {@code docs/architecture/ldap-write-surface.md}.
     */
    String value() default "";
}
