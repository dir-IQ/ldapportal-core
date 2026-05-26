// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link java.util.UUID} parameter as the directory identifier for
 * permission checks.
 *
 * <p>The {@link FeaturePermissionAspect} looks for this annotation (falling
 * back to parameter-name matching for backwards compatibility) when resolving
 * the directory scope for {@link RequiresFeature}-annotated methods.</p>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DirectoryId {
}
