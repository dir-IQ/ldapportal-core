// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.auth;

import com.ldapportal.entity.enums.SuperadminPermission;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a superadmin controller method (or whole controller class) as requiring
 * a specific {@link SuperadminPermission}.
 *
 * <p>This is the system-scoped counterpart to {@link RequiresFeature}. Unlike
 * that annotation it needs no {@code directoryId} — superadmin permissions are
 * global. It layers on top of the existing {@code hasRole('SUPERADMIN')} gate:
 * the caller must already be a superadmin <em>and</em> hold this permission
 * (owners — holders of {@link SuperadminPermission#MANAGE_SUPERADMINS} — hold
 * all of them).</p>
 *
 * <p>Enforced by {@link SuperadminPermissionAspect}. A method-level annotation
 * takes precedence over a class-level one.</p>
 *
 * <pre>{@code
 * @RequiresSuperadminPermission(SuperadminPermission.MANAGE_APPLICATION_ACCOUNTS)
 * public ResponseEntity<Void> create(...) { ... }
 * }</pre>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresSuperadminPermission {
    SuperadminPermission value();
}
