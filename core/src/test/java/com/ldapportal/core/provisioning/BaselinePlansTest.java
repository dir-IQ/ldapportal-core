// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.provisioning;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.DirectoryType;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the byte-shape of the baseline plans — the "no interceptor"
 * default that {@link ProvisioningInterceptorChain} falls back to.
 * If any of these change, every existing LDAP-write test in
 * {@code LdapUserServiceTest} / {@code LdapGroupServiceTest} will
 * either pass or fail in lockstep; this file is the place to spot
 * a behaviour drift early.
 */
class BaselinePlansTest {

    @Test
    void userCreate_singleAddStep_withProvisionedAttributes() {
        UserCreatePayload payload = UserCreatePayload.of(
                "uid=alice,ou=users,dc=example,dc=com",
                Map.of(
                        "objectClass", List.of("inetOrgPerson", "person"),
                        "cn", List.of("Alice"),
                        "sn", List.of("Apple")));

        UserCreatePlan plan = BaselinePlans.userCreate(payload);

        assertThat(plan.steps()).hasSize(1);
        assertThat(plan.steps().get(0)).isInstanceOf(AddStep.class);
        AddStep step = (AddStep) plan.steps().get(0);
        assertThat(step.targetDn()).isEqualTo("uid=alice,ou=users,dc=example,dc=com");
        assertThat(step.onFailure()).isEqualTo(StepFailurePolicy.ABORT);
        assertThat(step.attributes()).extracting("name")
                .containsExactlyInAnyOrder("objectClass", "cn", "sn");
        assertThat(plan.compensation()).isEmpty();
    }

    @Test
    void userDelete_singleDelStep() {
        DeletePlan plan = BaselinePlans.userDelete("uid=alice,ou=users,dc=example,dc=com");

        assertThat(plan.steps()).hasSize(1);
        assertThat(plan.steps().get(0)).isInstanceOf(DeleteStep.class);
        DeleteStep step = (DeleteStep) plan.steps().get(0);
        assertThat(step.targetDn()).isEqualTo("uid=alice,ou=users,dc=example,dc=com");
        assertThat(step.onFailure()).isEqualTo(StepFailurePolicy.ABORT);
    }

    @Test
    void passwordSet_nonAd_replacesUserPassword_plaintext() {
        DirectoryConnection dc = openldapDc();
        PasswordPlan plan = BaselinePlans.passwordSet(dc,
                "uid=alice,ou=users,dc=example,dc=com",
                new PasswordSetPayload("hunter2"));

        assertThat(plan.steps()).hasSize(1);
        ModifyStep step = (ModifyStep) plan.steps().get(0);
        assertThat(step.mods()).hasSize(1);
        Modification mod = step.mods().get(0);
        assertThat(mod.getModificationType()).isEqualTo(ModificationType.REPLACE);
        assertThat(mod.getAttributeName()).isEqualTo("userPassword");
        assertThat(mod.getValues()[0]).isEqualTo("hunter2");
    }

    @Test
    void passwordSet_ad_replacesUnicodePwd_quotedUtf16LeEncoded() {
        // AD's famous UTF-16LE-encoded-quote-wrapped unicodePwd
        // contract — exact match below.
        DirectoryConnection dc = adDc();
        PasswordPlan plan = BaselinePlans.passwordSet(dc,
                "CN=Alice,OU=Users,DC=example,DC=com",
                new PasswordSetPayload("hunter2"));

        assertThat(plan.steps()).hasSize(1);
        ModifyStep step = (ModifyStep) plan.steps().get(0);
        Modification mod = step.mods().get(0);
        assertThat(mod.getModificationType()).isEqualTo(ModificationType.REPLACE);
        assertThat(mod.getAttributeName()).isEqualTo("unicodePwd");

        byte[] expected = "\"hunter2\"".getBytes(Charset.forName("UTF-16LE"));
        assertThat(mod.getValueByteArrays()[0]).isEqualTo(expected);
    }

    @Test
    void groupMembership_proceedsWithSingleAddModification() {
        GroupMemberPlan plan = BaselinePlans.groupMembership(
                "cn=eng,ou=groups,dc=example,dc=com",
                "member",
                "uid=alice,ou=users,dc=example,dc=com");

        assertThat(plan.isRefused()).isFalse();
        assertThat(plan.steps()).hasSize(1);
        ModifyStep step = (ModifyStep) plan.steps().get(0);
        assertThat(step.targetDn()).isEqualTo("cn=eng,ou=groups,dc=example,dc=com");
        Modification mod = step.mods().get(0);
        assertThat(mod.getModificationType()).isEqualTo(ModificationType.ADD);
        assertThat(mod.getAttributeName()).isEqualTo("member");
        assertThat(mod.getValues()[0]).isEqualTo("uid=alice,ou=users,dc=example,dc=com");
    }

    // ── fixtures ─────────────────────────────────────────────────────

    private static DirectoryConnection openldapDc() {
        DirectoryConnection dc = new DirectoryConnection();
        dc.setDirectoryType(DirectoryType.OPENLDAP);
        return dc;
    }

    private static DirectoryConnection adDc() {
        DirectoryConnection dc = new DirectoryConnection();
        dc.setDirectoryType(DirectoryType.ACTIVE_DIRECTORY);
        return dc;
    }
}
