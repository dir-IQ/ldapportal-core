// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.provisioning;

import com.ldapportal.entity.DirectoryConnection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The single entry point services use to compute a provisioning
 * plan. Wraps the Spring-injected list of {@link ProvisioningInterceptor}
 * beans and falls back to {@link BaselinePlans} when the list is
 * empty.
 *
 * <p>v1 design notes:
 * <ul>
 *   <li>When no interceptor is registered (the community / commercial-
 *       no-addons case), every {@code plan*} method delegates straight
 *       to {@link BaselinePlans} — the LDAP traffic that comes out the
 *       other side is byte-identical to the pre-SPI service layer.
 *       This is what the invariant test pins down.</li>
 *   <li>When one interceptor is registered (the post-P2 case, with the
 *       ISVA addon loaded), every {@code plan*} method delegates to
 *       it. The interceptor decides per-directory whether to produce
 *       a specialised plan or to return {@link BaselinePlans} for
 *       directories it doesn't apply to.</li>
 *   <li>The multi-interceptor case isn't a real scenario yet. The
 *       v1 implementation picks the first interceptor in the list
 *       and ignores the rest, logging a warning so the regression is
 *       visible if a second one ever lands. v2 will define folding
 *       semantics if/when there's a use case.</li>
 * </ul>
 *
 * <p>Bean is registered explicitly (not auto-detected from another
 * config) so tests can construct it directly with a hand-rolled
 * list of interceptors — see {@link #ProvisioningInterceptorChain(List)}.</p>
 */
@Component
@Slf4j
public class ProvisioningInterceptorChain {

    private final List<ProvisioningInterceptor> interceptors;

    @Autowired
    public ProvisioningInterceptorChain(List<ProvisioningInterceptor> interceptors) {
        // Defensive copy — Spring's injected list is immutable in
        // practice but null-tolerance is cheap.
        this.interceptors = interceptors == null ? List.of() : List.copyOf(interceptors);
        if (this.interceptors.isEmpty()) {
            log.debug("No ProvisioningInterceptor beans registered — "
                    + "plans use BaselinePlans (pre-SPI behaviour).");
        } else if (this.interceptors.size() > 1) {
            log.warn("Multiple ProvisioningInterceptor beans registered ({}). "
                    + "v1 uses only the first; the others are ignored.",
                    this.interceptors.size());
        } else {
            log.info("ProvisioningInterceptor registered: {}",
                    this.interceptors.get(0).getClass().getSimpleName());
        }
    }

    public UserCreatePlan planUserCreate(DirectoryConnection dir,
                                         UserCreatePayload payload,
                                         ProvisioningContext ctx) {
        return interceptors.isEmpty()
                ? BaselinePlans.userCreate(payload)
                : interceptors.get(0).planUserCreate(dir, payload, ctx);
    }

    public DeletePlan planUserDelete(DirectoryConnection dir,
                                     String demographicDn,
                                     ProvisioningContext ctx) {
        return interceptors.isEmpty()
                ? BaselinePlans.userDelete(demographicDn)
                : interceptors.get(0).planUserDelete(dir, demographicDn, ctx);
    }

    public PasswordPlan planPasswordSet(DirectoryConnection dir,
                                         String demographicDn,
                                         PasswordSetPayload payload,
                                         ProvisioningContext ctx) {
        return interceptors.isEmpty()
                ? BaselinePlans.passwordSet(dir, demographicDn, payload)
                : interceptors.get(0).planPasswordSet(dir, demographicDn, payload, ctx);
    }

    public GroupMemberPlan planGroupMembership(DirectoryConnection dir,
                                                String groupDn,
                                                String memberAttribute,
                                                String memberValue,
                                                ProvisioningContext ctx) {
        return interceptors.isEmpty()
                ? BaselinePlans.groupMembership(groupDn, memberAttribute, memberValue)
                : interceptors.get(0).planGroupMembership(dir, groupDn, memberAttribute, memberValue, ctx);
    }

    /** True iff at least one interceptor is registered. Used by services
     * that want to keep the old code path when no interceptor exists —
     * not strictly necessary (plans collapse to baseline) but useful
     * for incrementally adopting the SPI in v1. */
    public boolean hasInterceptors() {
        return !interceptors.isEmpty();
    }
}
