// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.provisioning;

/**
 * Inputs to {@link ProvisioningInterceptor#planPasswordSet}. Just
 * the new cleartext password; the target DN and directory metadata
 * arrive as separate method args because they're shared with other
 * plan methods.
 */
public record PasswordSetPayload(String newPassword) {}
