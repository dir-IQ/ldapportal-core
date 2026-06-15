// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.provisioning;

import com.ldapportal.entity.DirectoryConnection;

/**
 * SPI implemented by addons / commercial modules that need to
 * customise LDAP write semantics on a per-directory basis. Each
 * method returns a {@code *Plan} — an ordered list of
 * {@link LdapOperationStep}s — that the executor walks step by step.
 *
 * <p>Originally needed for the ISVA full-mode integration where:
 * <ul>
 *   <li>Creating a user in <em>linked</em> topology fans out into
 *       two LDAP adds against different DNs (demographic entry +
 *       paired secUser entry under the management DIT).</li>
 *   <li>Deleting a user in any ISVA topology becomes a MODIFY
 *       (set {@code secAcctValid=FALSE}) rather than a DEL, so
 *       policy associations and audit trail survive.</li>
 * </ul>
 * The plan abstraction is generic — future vendor integrations
 * (Okta connector, etc.) reuse it via the same SPI.</p>
 *
 * <p>When no interceptor is registered against a given directory,
 * {@link ProvisioningInterceptorChain} falls back to
 * {@link BaselinePlans} which produces single-step plans matching
 * the pre-SPI behaviour byte-for-byte. Existing deployments see
 * no observable change from the SPI's existence.</p>
 *
 * <p>Per-interceptor "does this apply to this directory?" filtering
 * is the implementation's responsibility — interceptors that don't
 * apply to a given directory return the baseline plan (typically
 * by delegating to {@link BaselinePlans}). The chain doesn't try
 * to route requests; v1 keeps things simple by assuming at most
 * one interceptor is registered globally.</p>
 *
 * <p>Every method takes a {@link ProvisioningContext} carrying
 * per-operation context — today the resolved provisioning-profile id,
 * which lets an interceptor make per-profile decisions (e.g. ISVA
 * exempting a {@code FORCE_OFF} profile). The context may be
 * {@link ProvisioningContext#empty()} when no profile was resolved;
 * interceptors must tolerate that and fall back to directory-level
 * behaviour.</p>
 */
public interface ProvisioningInterceptor {

    UserCreatePlan planUserCreate(DirectoryConnection dir,
                                  UserCreatePayload payload,
                                  ProvisioningContext ctx);

    DeletePlan planUserDelete(DirectoryConnection dir,
                              String demographicDn,
                              ProvisioningContext ctx);

    PasswordPlan planPasswordSet(DirectoryConnection dir,
                                 String demographicDn,
                                 PasswordSetPayload payload,
                                 ProvisioningContext ctx);

    GroupMemberPlan planGroupMembership(DirectoryConnection dir,
                                        String groupDn,
                                        String memberAttribute,
                                        String memberValue,
                                        ProvisioningContext ctx);
}
