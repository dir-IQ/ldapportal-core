// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.auth;

import com.ldapportal.entity.enums.FeatureKey;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method as requiring a specific {@link FeatureKey}
 * permission.
 *
 * <p>The {@link FeaturePermissionAspect} intercepts annotated methods,
 * extracts the current {@link AuthPrincipal} from
 * {@link org.springframework.security.core.context.SecurityContextHolder},
 * and calls
 * {@link PermissionService#requireFeature(AuthPrincipal, java.util.UUID, FeatureKey)}.
 * </p>
 *
 * <p>The target method <em>must</em> have a {@link java.util.UUID} parameter
 * annotated with {@link DirectoryId} (or, as a legacy fallback, named
 * {@code directoryId}) so the aspect can resolve the directory scope.</p>
 *
 * <pre>{@code
 * @RequiresFeature(FeatureKey.USER_CREATE)
 * public ResponseEntity<Void> createUser(@DirectoryId @PathVariable UUID directoryId,
 *                                        @RequestBody CreateUserRequest req) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresFeature {
    FeatureKey value();
}
