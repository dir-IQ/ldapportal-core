// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.provisioning;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.DirectoryType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the chain's two routing modes — falls back to
 * {@link BaselinePlans} when empty, delegates to the first
 * registered interceptor otherwise. The "multiple interceptors"
 * path is also covered because it's the one most likely to
 * misbehave when P2 lands a real interceptor.
 */
class ProvisioningInterceptorChainTest {

    private final DirectoryConnection dc = directoryConnection();

    @Test
    void emptyChain_userCreate_fallsBackToBaselinePlans() {
        ProvisioningInterceptorChain chain =
                new ProvisioningInterceptorChain(List.of());

        UserCreatePlan plan = chain.planUserCreate(dc,
                UserCreatePayload.of("uid=a,dc=x", Map.of()), ProvisioningContext.empty());

        // Single-step ADD, exactly what BaselinePlans would produce.
        assertThat(plan.steps()).hasSize(1);
        assertThat(plan.steps().get(0)).isInstanceOf(AddStep.class);
    }

    @Test
    void emptyChain_userDelete_fallsBackToBaselinePlans() {
        ProvisioningInterceptorChain chain =
                new ProvisioningInterceptorChain(List.of());

        DeletePlan plan = chain.planUserDelete(dc, "uid=a,dc=x", ProvisioningContext.empty());

        assertThat(plan.steps()).hasSize(1);
        assertThat(plan.steps().get(0)).isInstanceOf(DeleteStep.class);
    }

    @Test
    void emptyChain_passwordSet_fallsBackToBaselinePlans() {
        ProvisioningInterceptorChain chain =
                new ProvisioningInterceptorChain(List.of());

        PasswordPlan plan = chain.planPasswordSet(dc, "uid=a,dc=x",
                new PasswordSetPayload("pw"), ProvisioningContext.empty());

        assertThat(plan.steps()).hasSize(1);
        assertThat(plan.steps().get(0)).isInstanceOf(ModifyStep.class);
    }

    @Test
    void emptyChain_groupMembership_fallsBackToProceedingPlan() {
        ProvisioningInterceptorChain chain =
                new ProvisioningInterceptorChain(List.of());

        GroupMemberPlan plan = chain.planGroupMembership(dc,
                "cn=g,dc=x", "member", "uid=a,dc=x", ProvisioningContext.empty());

        assertThat(plan.isRefused()).isFalse();
        assertThat(plan.steps()).hasSize(1);
    }

    @Test
    void nullList_treatedAsEmpty() {
        // Defensive: tests / direct construction may pass null even
        // though Spring's DI wouldn't. Same fallback as empty.
        ProvisioningInterceptorChain chain =
                new ProvisioningInterceptorChain(null);

        assertThat(chain.hasInterceptors()).isFalse();
        DeletePlan plan = chain.planUserDelete(dc, "uid=a,dc=x", ProvisioningContext.empty());
        assertThat(plan.steps().get(0)).isInstanceOf(DeleteStep.class);
    }

    @Test
    void singleInterceptor_delegates() {
        // The interceptor returns a sentinel two-step plan so we can
        // verify it (not the baseline single-step) is what came back.
        ProvisioningInterceptor sentinel = new SentinelInterceptor();
        ProvisioningInterceptorChain chain =
                new ProvisioningInterceptorChain(List.of(sentinel));

        UserCreatePlan plan = chain.planUserCreate(dc,
                UserCreatePayload.of("uid=a,dc=x", Map.of()), ProvisioningContext.empty());

        assertThat(plan.steps()).hasSize(2);  // sentinel returns 2-step plan
    }

    @Test
    void multipleInterceptors_firstWins() {
        // v1 chain picks the first interceptor and logs a warning;
        // this test pins that behaviour. v2 may define folding
        // semantics; until then, "first wins" is the contract.
        ProvisioningInterceptor first = new SentinelInterceptor();   // 2-step plan
        ProvisioningInterceptor second = new SentinelInterceptor3(); // 3-step plan
        ProvisioningInterceptorChain chain =
                new ProvisioningInterceptorChain(List.of(first, second));

        UserCreatePlan plan = chain.planUserCreate(dc,
                UserCreatePayload.of("uid=a,dc=x", Map.of()), ProvisioningContext.empty());

        assertThat(plan.steps()).hasSize(2);  // first won
    }

    @Test
    void hasInterceptors_reflectsState() {
        assertThat(new ProvisioningInterceptorChain(List.of()).hasInterceptors())
                .isFalse();
        assertThat(new ProvisioningInterceptorChain(List.of(new SentinelInterceptor())).hasInterceptors())
                .isTrue();
    }

    // ── stub interceptors ────────────────────────────────────────────

    /**
     * Returns deliberately-non-baseline plans (multi-step) so tests
     * can tell a "delegation worked" pass from a "baseline accidentally
     * ran" pass.
     */
    private static class SentinelInterceptor implements ProvisioningInterceptor {
        @Override
        public UserCreatePlan planUserCreate(DirectoryConnection dir, UserCreatePayload payload, ProvisioningContext ctx) {
            return new UserCreatePlan(
                    List.of(
                            AddStep.of("dn1=sentinel", List.of()),
                            AddStep.of("dn2=sentinel", List.of())),
                    java.util.Optional.empty());
        }
        @Override
        public DeletePlan planUserDelete(DirectoryConnection dir, String demographicDn, ProvisioningContext ctx) {
            return DeletePlan.singleStep(DeleteStep.of(demographicDn));
        }
        @Override
        public PasswordPlan planPasswordSet(DirectoryConnection dir, String demographicDn, PasswordSetPayload payload, ProvisioningContext ctx) {
            return BaselinePlans.passwordSet(dir, demographicDn, payload);
        }
        @Override
        public GroupMemberPlan planGroupMembership(DirectoryConnection dir, String groupDn, String memberAttribute, String memberValue, ProvisioningContext ctx) {
            return BaselinePlans.groupMembership(groupDn, memberAttribute, memberValue);
        }
    }

    private static class SentinelInterceptor3 extends SentinelInterceptor {
        @Override
        public UserCreatePlan planUserCreate(DirectoryConnection dir, UserCreatePayload payload, ProvisioningContext ctx) {
            return new UserCreatePlan(
                    List.of(
                            AddStep.of("dn1=other", List.of()),
                            AddStep.of("dn2=other", List.of()),
                            AddStep.of("dn3=other", List.of())),
                    java.util.Optional.empty());
        }
    }

    private static DirectoryConnection directoryConnection() {
        DirectoryConnection dc = new DirectoryConnection();
        dc.setDirectoryType(DirectoryType.OPENLDAP);
        return dc;
    }
}
