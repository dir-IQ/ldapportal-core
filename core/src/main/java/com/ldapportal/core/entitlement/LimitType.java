// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.entitlement;

/**
 * Resource types that carry per-edition quotas. Enforcement is added in
 * Phase 7 via {@code UsageLimitService#requireWithinLimit}; this enum is
 * declared now so {@link License} can carry the limit map from the start.
 *
 * <p>See {@code docs/edition-boundary.md} §Limits for the per-edition caps.</p>
 */
public enum LimitType {
    DIRECTORIES,
    ADMIN_ACCOUNTS,
    PROFILES_PER_DIRECTORY,
    /** Directories managed via the Terraform provider (separate cap from total). */
    TERRAFORM_DIRECTORIES,
    /** Profiles managed via the Terraform provider (separate cap from total). */
    TERRAFORM_PROFILES,
    /** Active event-bus subscribers. Zero for tiers without the events module. */
    EVENT_SUBSCRIBERS,
}
